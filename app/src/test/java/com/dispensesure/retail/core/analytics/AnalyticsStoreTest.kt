package com.dispensesure.retail.core.analytics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.analytics.model.AnalyticsDuration
import com.dispensesure.retail.core.analytics.model.AnalyticsField
import com.dispensesure.retail.core.analytics.model.AnalyticsIncrement
import com.dispensesure.retail.core.analytics.model.AnalyticsWorkType
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.room.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `apply with all-empty arguments writes nothing`() = runTest {
        store.apply(emptyList(), emptyList(), emptyList())
        assertEquals(0, db.dailyAnalyticsDao().getAll().size)
    }

    @Test
    fun `first apply creates row with durationMin sentinel -1 and context stamped`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val rows = db.dailyAnalyticsDao().getAll()
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
        val rows = db.dailyAnalyticsDao().getAll()
        assertEquals(1, rows.size)
        assertEquals(2L, rows[0].dispenseNormalCount)
    }

    @Test
    fun `duration sentinel replaced on first value, min lowered by smaller value`() = runTest {
        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 120L)), emptyList())
        var row = db.dailyAnalyticsDao().getAll()[0]
        assertEquals(120L, row.dispenseNormalDurationMin)
        assertEquals(120L, row.dispenseNormalDurationMax)
        assertEquals(120L, row.dispenseNormalDurationSum)
        assertEquals(1L, row.dispenseNormalDurationSamples)

        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 60L)), emptyList())
        row = db.dailyAnalyticsDao().getAll()[0]
        assertEquals(60L, row.dispenseNormalDurationMin)
        assertEquals(120L, row.dispenseNormalDurationMax)
        assertEquals(180L, row.dispenseNormalDurationSum)

        store.apply(emptyList(), listOf(AnalyticsDuration(AnalyticsWorkType.DISPENSE_NORMAL, 200L)), emptyList())
        row = db.dailyAnalyticsDao().getAll()[0]
        assertEquals(60L, row.dispenseNormalDurationMin)
        assertEquals(200L, row.dispenseNormalDurationMax)
    }

    @Test
    fun `INVENTORY_CYCLE_NDC_COUNT increments accumulate`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.INVENTORY_CYCLE_NDC_COUNT, 1L)), emptyList(), emptyList())
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.INVENTORY_CYCLE_NDC_COUNT, 1L)), emptyList(), emptyList())
        val ndcCount = db.dailyAnalyticsDao().getAll()[0].inventoryCycleNdcCount
        assertEquals(2L, ndcCount)
    }

    @Test
    fun `day rollover creates a second row`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        // advance fake clock past midnight
        fakeNow += 24L * 60 * 60 * 1000
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        assertEquals(2, db.dailyAnalyticsDao().getAll().size)
    }

    @Test
    fun `markFailed backoff schedule — attempt 1 is 30s, attempt 5 is 1h, attempt 6 stays 1h`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val id = db.dailyAnalyticsDao().getAll()[0].id

        val backoffs = mapOf(1 to 30L, 2 to 120L, 3 to 480L, 4 to 1800L, 5 to 3600L, 6 to 3600L)
        var expectedAttempts = 0
        for ((attemptNum, expectedBackoffSec) in backoffs) {
            val baseNow = fakeNow
            store.markFailed(listOf(id))
            expectedAttempts++
            val row = db.dailyAnalyticsDao().getAll()[0]
            assertEquals("attempt $attemptNum attempts count", expectedAttempts, row.uploadAttempts)
            val backoffMs = row.nextAttemptAt - baseNow
            assertEquals("attempt $attemptNum backoff", expectedBackoffSec * 1000L, backoffMs)
        }
    }

    @Test
    fun `markPoison sets uploadAttempts to 10`() = runTest {
        store.apply(listOf(AnalyticsIncrement(AnalyticsField.DISPENSE_NORMAL_COUNT, 1L)), emptyList(), emptyList())
        val id = db.dailyAnalyticsDao().getAll()[0].id
        store.markPoison(listOf(id), "422 schema rejection")
        assertEquals(10, db.dailyAnalyticsDao().getAll()[0].uploadAttempts)
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

        val remaining = db.dailyAnalyticsDao().getAll()
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
