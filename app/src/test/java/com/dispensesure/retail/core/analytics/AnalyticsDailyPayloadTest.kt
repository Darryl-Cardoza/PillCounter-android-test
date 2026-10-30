package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.PmsLifecycle
import com.dispensesure.retail.core.analytics.collect.PmsLifecycleBlock
import com.dispensesure.retail.core.analytics.store.DailyAnalyticsEntity
import com.dispensesure.retail.core.analytics.upload.AnalyticsDailyRequest
import com.dispensesure.retail.core.analytics.upload.toRecord
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class AnalyticsDailyPayloadTest {

    private lateinit var moshi: Moshi

    @Before
    fun setUp() {
        moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    }

    private fun entity(
        cycleNdcCount: Long = 0L,
        stockNdcCount: Long = 0L,
        normalMin: Long = -1L,
        manualTransactions: Long = 0L,
        normalCount: Long = 0L,
        controlledCount: Long = 0L,
        hazardousCount: Long = 0L,
    ) = DailyAnalyticsEntity(
        pharmacyUserId = "u1",
        terminalName = "term1",
        operatorId = "op1",
        businessDate = "2025-01-15",
        region = "US",
        appVersion = "1.2.3",
        timezoneOffsetMin = -300,
        dispenseNormalCount = normalCount,
        dispenseNormalDurationMin = normalMin,
        inventoryCycleNdcCount = cycleNdcCount,
        inventoryStockNdcCount = stockNdcCount,
        originManualTransactions = manualTransactions,
        dispenseControlledCount = controlledCount,
        dispenseHazardousCount = hazardousCount,
    )

    private fun zeroPms() = PmsLifecycle(
        PmsLifecycleBlock(0L, 0L, 0L, 0L),
        PmsLifecycleBlock(0L, 0L, 0L, 0L),
    )

    @Test
    fun `revision is always 0`() {
        val record = entity().toRecord(zeroPms())
        assertEquals(0, record.revision)
    }

    @Test
    fun `duration min sentinel -1 encodes to 0`() {
        val record = entity(normalMin = -1L).toRecord(zeroPms())
        assertEquals(0L, record.volume.dispense.normal.durationSeconds.min)
    }

    @Test
    fun `duration min real value encodes as-is`() {
        val record = entity(normalMin = 42L).toRecord(zeroPms())
        assertEquals(42L, record.volume.dispense.normal.durationSeconds.min)
    }

    @Test
    fun `ndc_scanned equals inventoryCycleNdcCount`() {
        val record = entity(cycleNdcCount = 3L).toRecord(zeroPms())
        assertEquals(3L, record.volume.inventory.cycleCount.ndcScanned)
    }

    @Test
    fun `ndc_scanned is 0 when inventoryCycleNdcCount is 0`() {
        val record = entity(cycleNdcCount = 0L).toRecord(zeroPms())
        assertEquals(0L, record.volume.inventory.cycleCount.ndcScanned)
    }

    @Test
    fun `pms_transactions equals total volume minus manual`() {
        val record = entity(normalCount = 10L, manualTransactions = 3L).toRecord(zeroPms())
        assertEquals(7L, record.origin.pmsTransactions)
    }

    @Test
    fun `pms_transactions floored at 0 when manual exceeds volume`() {
        val record = entity(normalCount = 2L, manualTransactions = 5L).toRecord(zeroPms())
        assertEquals(0L, record.origin.pmsTransactions)
    }

    @Test
    fun `JSON key set matches spec exactly at record level`() {
        val record = entity().toRecord(zeroPms())
        val request = AnalyticsDailyRequest(deviceKey = "dev1", records = listOf(record))
        val adapter = moshi.adapter(AnalyticsDailyRequest::class.java)
        val json = adapter.toJson(request)
        val recordJson = JSONObject(json).getJSONArray("records").getJSONObject(0)

        val expectedKeys = setOf(
            "business_date", "revision", "terminal_name", "operator_id",
            "context", "volume", "origin", "pms", "safety", "expiry", "compliance", "accuracy",
        )
        assertEquals(expectedKeys, recordJson.keys().asSequence().toSet())
    }

    @Test
    fun `JSON does not contain local-only columns`() {
        val record = entity().toRecord(zeroPms())
        val request = AnalyticsDailyRequest(deviceKey = "dev1", records = listOf(record))
        val adapter = moshi.adapter(AnalyticsDailyRequest::class.java)
        val json = adapter.toJson(request)

        assertFalse("upload_attempts must not appear", json.contains("\"upload_attempts\""))
        assertFalse("next_attempt_at must not appear", json.contains("\"next_attempt_at\""))
        assertFalse("pharmacy_user_id must not appear", json.contains("\"pharmacy_user_id\""))
        assertFalse("duration_samples must not appear", json.contains("\"duration_samples\""))
        assertFalse("safety_expired_blocked must not appear", json.contains("\"expired_blocked\""))
        assertFalse("accuracy_count_mismatch must not appear", json.contains("\"count_mismatch\""))
        assertFalse("accuracy_manual_entry must not appear", json.contains("\"manual_entry\""))
    }

    @Test
    fun `dispense block contains normal, controlled, hazardous`() {
        val record = entity().toRecord(zeroPms())
        val request = AnalyticsDailyRequest(deviceKey = "dev1", records = listOf(record))
        val adapter = moshi.adapter(AnalyticsDailyRequest::class.java)
        val json = adapter.toJson(request)
        val dispenseJson = JSONObject(json)
            .getJSONArray("records").getJSONObject(0)
            .getJSONObject("volume")
            .getJSONObject("dispense")

        assertEquals(setOf("normal", "controlled", "hazardous"), dispenseJson.keys().asSequence().toSet())
    }
}
