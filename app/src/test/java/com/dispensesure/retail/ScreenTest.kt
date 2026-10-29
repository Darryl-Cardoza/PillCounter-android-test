package com.dispensesure.retail

import com.dispensesure.retail.core.room.models.enums.ScanType
import com.dispensesure.retail.feature.history.domain.model.HistoryMode
import com.dispensesure.retail.navigation.Screen
import org.junit.Assert
import org.junit.Test

/**
 * Unit tests for [Screen] (declared in the default/root package, like the source).
 * Covers every screen's `route`, `createRoute(...)`, and `navArguments` initializer.
 */
class ScreenTest {

    // ─────────────────────────── argument-less screens ───────────────────────────

    @Test
    fun `argument-less routes`() {
        Assert.assertEquals("login", Screen.Login.route)
        Assert.assertEquals("dashboard", Screen.Dashboard.route)
        Assert.assertEquals("menu", Screen.Menu.route)
        Assert.assertEquals("settings", Screen.Settings.route)
        Assert.assertEquals("history_detail", Screen.HistoryDetail.route)
        Assert.assertEquals("profile", Screen.Profile.route)
        Assert.assertEquals("unsynced_transaction_screen", Screen.UnsyncedTransactionScreen.route)
    }

    // ─────────────────────────── InventoryScan ───────────────────────────

    @Test
    fun `InventoryScan route createRoute and args`() {
        Assert.assertEquals(
            "inventory_scan?batch_id={batch_id}&bucket_id={bucket_id}",
            Screen.InventoryScan.route
        )
        Assert.assertEquals("batch_id", Screen.InventoryScan.ARG_BATCH_ID)
        Assert.assertEquals("bucket_id", Screen.InventoryScan.ARG_BUCKET_ID)
        Assert.assertEquals(
            "inventory_scan?batch_id=0&bucket_id=",
            Screen.InventoryScan.createRoute()
        )
        Assert.assertEquals(
            "inventory_scan?batch_id=7&bucket_id=B1",
            Screen.InventoryScan.createRoute(batchId = 7L, bucketId = "B1")
        )
        // null bucketId coerces to empty
        Assert.assertEquals(
            "inventory_scan?batch_id=7&bucket_id=",
            Screen.InventoryScan.createRoute(batchId = 7L, bucketId = null)
        )
        Assert.assertEquals(2, Screen.InventoryScan.navArguments.size)
    }

    // ─────────────────────────── BatchHistoryDetail ───────────────────────────

    @Test
    fun `BatchHistoryDetail route createRoute and args`() {
        Assert.assertEquals("batch_history_detail/{batch_id}", Screen.BatchHistoryDetail.route)
        Assert.assertEquals("batch_id", Screen.BatchHistoryDetail.ARG_BATCH_ID)
        Assert.assertEquals("batch_history_detail/9", Screen.BatchHistoryDetail.createRoute(9L))
        Assert.assertEquals(1, Screen.BatchHistoryDetail.navArguments.size)
    }

    // ─────────────────────────── History ───────────────────────────

    @Test
    fun `History route createRoute and args`() {
        Assert.assertEquals("history/{type}", Screen.History.route)
        Assert.assertEquals("type", Screen.History.ARG_TYPE)
        Assert.assertEquals("history/NORMAL", Screen.History.createRoute(HistoryMode.NORMAL))
        Assert.assertEquals("history/DISPENSE", Screen.History.createRoute(HistoryMode.DISPENSE))
        Assert.assertEquals(1, Screen.History.navArguments.size)
    }

    // ─────────────────────────── OtpVerify ───────────────────────────

    @Test
    fun `OtpVerify route createRoute and args`() {
        Assert.assertEquals(
            "otp_verify?email={email}&remember_me={remember_me}",
            Screen.OtpVerify.route
        )
        Assert.assertEquals("email", Screen.OtpVerify.ARG_EMAIL)
        Assert.assertEquals("remember_me", Screen.OtpVerify.ARG_REMEMBER_ME)
        Assert.assertEquals(
            "otp_verify?email=a@b.com&remember_me=true",
            Screen.OtpVerify.createRoute("a@b.com", rememberMe = true)
        )
        Assert.assertEquals(
            "otp_verify?email=a@b.com&remember_me=false",
            Screen.OtpVerify.createRoute("a@b.com", rememberMe = false)
        )
        Assert.assertEquals(2, Screen.OtpVerify.navArguments.size)
    }

    // ─────────────────────────── ScanBarcode ───────────────────────────

    @Test
    fun `ScanBarcode route createRoute and args`() {
        Assert.assertEquals(
            "scan_barcode/{type}/{batch_id}?txn_scan_type={txn_scan_type}",
            Screen.ScanBarcode.route
        )
        Assert.assertEquals("type", Screen.ScanBarcode.ARG_TYPE)
        Assert.assertEquals("batch_id", Screen.ScanBarcode.ARG_BATCH_ID)
        Assert.assertEquals("txn_scan_type", Screen.ScanBarcode.TXN_SCAN_TYPE)
        Assert.assertEquals(
            "scan_barcode/STOCK_COUNT/5?txn_scan_type=BARCODE",
            Screen.ScanBarcode.createRoute("STOCK_COUNT", ScanType.BARCODE, 5L)
        )
        Assert.assertEquals(3, Screen.ScanBarcode.navArguments.size)
    }

    // ─────────────────────────── PillCount ───────────────────────────

    @Test
    fun `PillCount route createRoute and args`() {
        Assert.assertEquals("pill_count/{type}", Screen.PillCount.route)
        Assert.assertEquals("type", Screen.PillCount.ARG_TYPE)
        Assert.assertEquals("pill_count/FIXED", Screen.PillCount.createRoute("FIXED"))
        Assert.assertEquals(1, Screen.PillCount.navArguments.size)
    }

    // ─────────────────────────── DispenseFlow ───────────────────────────

    @Test
    fun `DispenseFlow route and arg constants`() {
        Assert.assertEquals(
            "dispense_flow?from_hl7={from_hl7}&from_resume={from_resume}&from_queue={from_queue}",
            Screen.DispenseFlow.route
        )
        Assert.assertEquals("from_hl7", Screen.DispenseFlow.ARG_FROM_HL7)
        Assert.assertEquals("from_resume", Screen.DispenseFlow.ARG_FROM_RESUME)
        Assert.assertEquals("from_queue", Screen.DispenseFlow.ARG_FROM_QUEUE)
        Assert.assertEquals(3, Screen.DispenseFlow.navArguments.size)
    }

    @Test
    fun `DispenseFlow createRoute with defaults`() {
        Assert.assertEquals(
            "dispense_flow?from_hl7=false&from_resume=false&from_queue=false",
            Screen.DispenseFlow.createRoute()
        )
    }

    @Test
    fun `DispenseFlow createRoute with all args`() {
        Assert.assertEquals(
            "dispense_flow?from_hl7=true&from_resume=true&from_queue=true",
            Screen.DispenseFlow.createRoute(
                fromHl7 = true,
                fromResume = true,
                fromQueue = true,
            )
        )
    }

    // ─────────────────────────── LooseCount ───────────────────────────

    @Test
    fun `LooseCount route and arg constants`() {
        Assert.assertEquals(
            "loose_count?batch_id={batch_id}&bucket_id={bucket_id}&allowed_ndcs={allowed_ndcs}",
            Screen.LooseCount.route
        )
        Assert.assertEquals("batch_id", Screen.LooseCount.ARG_BATCH_ID)
        Assert.assertEquals("bucket_id", Screen.LooseCount.ARG_BUCKET_ID)
        Assert.assertEquals("allowed_ndcs", Screen.LooseCount.ARG_ALLOWED_NDCS)
        Assert.assertEquals("loose_count_batch_id", Screen.LooseCount.RESULT_BATCH_ID)
        Assert.assertEquals(3, Screen.LooseCount.navArguments.size)
    }

    @Test
    fun `LooseCount createRoute carries the batch, the bucket and the allowlist`() {
        Assert.assertEquals(
            "loose_count?batch_id=12&bucket_id=BKT&allowed_ndcs=n1,n2",
            Screen.LooseCount.createRoute(batchId = 12L, bucketId = "BKT", allowedNdcs = linkedSetOf("n1", "n2"))
        )
    }

    @Test
    fun `LooseCount createRoute with no batch, no bucket and no allowlist`() {
        Assert.assertEquals(
            "loose_count?batch_id=0&bucket_id=&allowed_ndcs=",
            Screen.LooseCount.createRoute(batchId = 0L, bucketId = null, allowedNdcs = emptySet())
        )
    }
}
