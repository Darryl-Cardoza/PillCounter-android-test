package com.dispensesure.retail.core.analytics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsDao
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity
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
class DailyAnalyticsDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DailyAnalyticsDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dailyAnalyticsDao()
    }

    @After
    fun tearDown() { db.close() }

    private fun row(
        businessDate: String = "2024-03-15",
        pharmacyUserId: String = "user1",
        terminalName: String = "T1",
        operatorId: String = "op1",
        region: String = "US",
    ) = DailyAnalyticsEntity(
        pharmacyUserId = pharmacyUserId,
        terminalName = terminalName,
        operatorId = operatorId,
        businessDate = businessDate,
        region = region,
        appVersion = "1.0.0",
        timezoneOffsetMin = -420,
    )

    @Test
    fun `unique key collision — insertIgnore returns -1 and findByKey still returns original`() = runTest {
        dao.insertIgnore(row())
        val secondInsert = dao.insertIgnore(row())

        assertEquals(-1L, secondInsert)
        val found = dao.findByKey("user1", "T1", "op1", "2024-03-15", "US")
        assertNotNull(found)
    }

    @Test
    fun `rowsReadyForUpload excludes today`() = runTest {
        dao.insertIgnore(row(businessDate = "2024-03-15"))
        val results = dao.rowsReadyForUpload(
            todayBusinessDate = "2024-03-15",
            nowMillis = 0L,
        )
        assertEquals(0, results.size)
    }

    @Test
    fun `rowsReadyForUpload excludes rows at 10 attempts`() = runTest {
        val inserted = dao.insertIgnore(row(businessDate = "2024-03-14"))
        val id = dao.findByKey("user1", "T1", "op1", "2024-03-14", "US")!!.id
        dao.update(row(businessDate = "2024-03-14").copy(id = id, uploadAttempts = 10))
        val results = dao.rowsReadyForUpload(
            todayBusinessDate = "2024-03-15",
            nowMillis = System.currentTimeMillis(),
        )
        assertEquals(0, results.size)
    }

    @Test
    fun `rowsReadyForUpload excludes rows with nextAttemptAt in the future`() = runTest {
        dao.insertIgnore(row(businessDate = "2024-03-14"))
        val entity = dao.findByKey("user1", "T1", "op1", "2024-03-14", "US")!!
        dao.update(entity.copy(nextAttemptAt = Long.MAX_VALUE))
        val results = dao.rowsReadyForUpload(
            todayBusinessDate = "2024-03-15",
            nowMillis = System.currentTimeMillis(),
        )
        assertEquals(0, results.size)
    }

    @Test
    fun `rowsReadyForUpload returns eligible past-day rows`() = runTest {
        dao.insertIgnore(row(businessDate = "2024-03-14"))
        val results = dao.rowsReadyForUpload(
            todayBusinessDate = "2024-03-15",
            nowMillis = System.currentTimeMillis(),
        )
        assertEquals(1, results.size)
    }

    @Test
    fun `pruneOlderThan deletes older rows and keeps newer ones`() = runTest {
        dao.insertIgnore(row(businessDate = "2024-01-01"))
        dao.insertIgnore(row(businessDate = "2024-03-14"))
        val deleted = dao.pruneOlderThan("2024-03-14")
        assertEquals(1, deleted)
        assertNull(dao.findByKey("user1", "T1", "op1", "2024-01-01", "US"))
        assertNotNull(dao.findByKey("user1", "T1", "op1", "2024-03-14", "US"))
    }
}
