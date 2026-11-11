package com.dispensesure.retail.core.analytics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsSetInsertion
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.analytics.store.AnalyticsNdcSet
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity
import com.dispensesure.retail.core.room.AppDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnalyticsStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: AnalyticsStore

    private var fakeNow = 1_711_497_600_000L // 2024-03-27 00:00:00 UTC (some fixed time)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = AnalyticsStore(
            dao = db.dailyAnalyticsDao(),
            countedDao = db.analyticsCountedDao(),
            clock = { fakeNow },
            pharmacyUserId = { "user1" },
            terminalName = { "T1" },
            operatorId = { "op1" },
            region = { "US" },
            appVersion = "1.0.0",
        )
    }

    @After
    fun tearDown() { db.close() }

    /**
     * Every analytics row, oldest first.
     *
     * Read with a raw query rather than a DAO method: production code only ever reaches a row by
     * its business key or by the upload query, so a `getAll` on the DAO would exist solely for
     * these assertions. Keeping it here leaves the production surface honest.
     */
    private fun allRows(): List<DailyAnalyticsEntity> {
        val cursor = db.query(SimpleSQLiteQuery("SELECT id FROM daily_analytics ORDER BY id"), null)
        val ids = mutableListOf<Long>()
        cursor.use { while (it.moveToNext()) ids.add(it.getLong(0)) }
        return runBlocking { ids.mapNotNull { db.dailyAnalyticsDao().findById(it) } }
    }

    @Test
    fun `apply with all-empty arguments writes nothing`() = runTest {
        store.apply(emptyList(), emptyList(), emptyList())
        assertEquals(0, allRows().size)
    }

    @Test
    fun `first apply creates row with durationMin sentinel -1 and context stamped`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val rows = allRows()
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(-1L, row.dispenseNormalDurationMin)
        assertEquals("1.0.0", row.appVersion)
        assertEquals("user1", row.pharmacyUserId)
    }

    @Test
    fun `two apply calls accumulate into one row`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val rows = allRows()
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].dispenseNormalCount)
    }

    @Test
    fun `duration sentinel replaced on first value, min lowered by smaller value`() = runTest {
        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 120L)), emptyList())
        var row = allRows()[0]
        assertEquals(120L, row.dispenseNormalDurationMin)
        assertEquals(120L, row.dispenseNormalDurationMax)
        assertEquals(120L, row.dispenseNormalDurationSum)
        assertEquals(1L, row.dispenseNormalDurationSamples)

        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 60L)), emptyList())
        row = allRows()[0]
        assertEquals(60L, row.dispenseNormalDurationMin)
        assertEquals(120L, row.dispenseNormalDurationMax)
        assertEquals(180L, row.dispenseNormalDurationSum)

        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 200L)), emptyList())
        row = allRows()[0]
        assertEquals(60L, row.dispenseNormalDurationMin)
        assertEquals(200L, row.dispenseNormalDurationMax)
    }

    /** `ndc_scanned` counts distinct codes, so inserting the same one twice must not move it. */
    @Test
    fun `NDC set holds distinct codes across applies`() = runTest {
        val cycle = AnalyticsWorkType.INVENTORY_CYCLE_COUNT
        store.apply(emptyList(), emptyList(), listOf(AnalyticsSetInsertion(cycle, "ndc-a")))
        store.apply(emptyList(), emptyList(), listOf(AnalyticsSetInsertion(cycle, "ndc-b")))
        store.apply(emptyList(), emptyList(), listOf(AnalyticsSetInsertion(cycle, "ndc-a")))

        assertEquals(2, AnalyticsNdcSet.size(allRows()[0].inventoryCycleNdcs))
    }

    @Test
    fun `NDC sets are kept apart per work type`() = runTest {
        store.apply(
            emptyList(),
            emptyList(),
            listOf(
                AnalyticsSetInsertion(AnalyticsWorkType.INVENTORY_CYCLE_COUNT, "ndc-a"),
                AnalyticsSetInsertion(AnalyticsWorkType.INVENTORY_STOCK_COUNT, "ndc-b"),
            ),
        )

        val row = allRows()[0]
        assertEquals(listOf("ndc-a"), AnalyticsNdcSet.decode(row.inventoryCycleNdcs))
        assertEquals(listOf("ndc-b"), AnalyticsNdcSet.decode(row.inventoryStockNdcs))
    }

    /** Insertions alone are a real change, so an otherwise-empty apply must still write. */
    @Test
    fun `apply with only insertions creates the row`() = runTest {
        store.apply(
            emptyList(),
            emptyList(),
            listOf(AnalyticsSetInsertion(AnalyticsWorkType.INVENTORY_STOCK_COUNT, "ndc-a")),
        )
        assertEquals(1, allRows().size)
    }

    /** The observer keys its de-duplication marker off this answer. */
    @Test
    fun `apply reports whether the row was written`() = runTest {
        assertTrue(
            store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        )

        val faultyStore = AnalyticsStore(
            dao = db.dailyAnalyticsDao(),
            countedDao = db.analyticsCountedDao(),
            clock = { fakeNow },
            pharmacyUserId = { throw RuntimeException("boom") },
            terminalName = { "t" },
            operatorId = { "o" },
            region = { "US" },
            appVersion = "1.0",
        )
        assertFalse(
            faultyStore.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        )
    }

    @Test
    fun `day rollover creates a second row`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        // advance fake clock past midnight
        fakeNow += 24L * 60 * 60 * 1000
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        assertEquals(2, allRows().size)
    }

    @Test
    fun `markFailed backoff schedule — attempt 1 is 30s, attempt 5 is 1h, attempt 6 stays 1h`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val id = allRows()[0].id

        val backoffs = mapOf(1 to 30L, 2 to 120L, 3 to 480L, 4 to 1800L, 5 to 3600L, 6 to 3600L)
        var expectedAttempts = 0
        for ((attemptNum, expectedBackoffSec) in backoffs) {
            val baseNow = fakeNow
            store.markFailed(listOf(id))
            expectedAttempts++
            val row = allRows()[0]
            assertEquals("attempt $attemptNum attempts count", expectedAttempts, row.uploadAttempts)
            val backoffMs = row.nextAttemptAt - baseNow
            assertEquals("attempt $attemptNum backoff", expectedBackoffSec * 1000L, backoffMs)
        }
    }

    @Test
    fun `markPoison sets uploadAttempts to 10`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val id = allRows()[0].id
        store.markPoison(listOf(id), "422 schema rejection")
        assertEquals(10, allRows()[0].uploadAttempts)
    }

    @Test
    fun `pruneIfNeeded deletes row 181 days old, keeps 179 days old`() = runTest {
        // Use two different operator ids so their 5-part keys are distinct
        val storeForOld = AnalyticsStore(
            dao = db.dailyAnalyticsDao(),
            countedDao = db.analyticsCountedDao(),
            clock = { fakeNow - 181L * 24 * 60 * 60 * 1000 },
            pharmacyUserId = { "user1" },
            terminalName = { "T1" },
            operatorId = { "old" },
            region = { "US" },
            appVersion = "1.0.0",
        )
        val storeForNew = AnalyticsStore(
            dao = db.dailyAnalyticsDao(),
            countedDao = db.analyticsCountedDao(),
            clock = { fakeNow - 179L * 24 * 60 * 60 * 1000 },
            pharmacyUserId = { "user1" },
            terminalName = { "T1" },
            operatorId = { "new" },
            region = { "US" },
            appVersion = "1.0.0",
        )
        storeForOld.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        storeForNew.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())

        store.pruneIfNeeded()

        val remaining = allRows()
        assertEquals(1, remaining.size)
        assertEquals("new", remaining[0].operatorId)
    }

    @Test
    fun `DAO throwing does not propagate out of apply`() = runTest {
        // A pharmacyUserId provider that throws simulates an unexpected runtime failure path
        val faultyStore = AnalyticsStore(
            dao = db.dailyAnalyticsDao(),
            countedDao = db.analyticsCountedDao(),
            clock = { fakeNow },
            pharmacyUserId = { throw RuntimeException("boom") },
            terminalName = { "t" },
            operatorId = { "o" },
            region = { "US" },
            appVersion = "1.0",
        )
        // Must not throw
        faultyStore.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
    }
}
