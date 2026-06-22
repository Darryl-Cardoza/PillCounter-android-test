package com.rite.pillcounting

import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.history.domain.model.HistoryMode
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
        Assert.assertEquals("save_history_for", Screen.SaveHistoryFor.route)
        Assert.assertEquals("require_double_count", Screen.RequireDoubleCount.route)
    }

    // ─────────────────────────── Batch ───────────────────────────

    @Test
    fun `Batch route createRoute and args`() {
        Assert.assertEquals("batch?batch_id={batch_id}", Screen.Batch.route)
        Assert.assertEquals("batch_id", Screen.Batch.ARG_BATCH_ID)
        Assert.assertEquals("batch?batch_id=0", Screen.Batch.createRoute())
        Assert.assertEquals("batch?batch_id=42", Screen.Batch.createRoute(42L))
        Assert.assertEquals(1, Screen.Batch.navArguments.size)
        Assert.assertEquals(Screen.Batch.ARG_BATCH_ID, Screen.Batch.navArguments[0].name)
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
            "otp_verify?email={email}&rememberMe={rememberMe}",
            Screen.OtpVerify.route
        )
        Assert.assertEquals("email", Screen.OtpVerify.ARG_EMAIL)
        Assert.assertEquals("rememberMe", Screen.OtpVerify.ARG_REMEMBER_ME)
        Assert.assertEquals(
            "otp_verify?email=a@b.com&rememberMe=true",
            Screen.OtpVerify.createRoute("a@b.com", true)
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
            "dispense_flow/{type}?from_hl7={from_hl7}&from_resume={from_resume}&batch_id={batch_id}&bucket_id={bucket_id}&from_queue={from_queue}&allowed_ndcs={allowed_ndcs}",
            Screen.DispenseFlow.route
        )
        Assert.assertEquals("type", Screen.DispenseFlow.ARG_TYPE)
        Assert.assertEquals("from_hl7", Screen.DispenseFlow.ARG_FROM_HL7)
        Assert.assertEquals("from_resume", Screen.DispenseFlow.ARG_FROM_RESUME)
        Assert.assertEquals("batch_id", Screen.DispenseFlow.ARG_BATCH_ID)
        Assert.assertEquals("bucket_id", Screen.DispenseFlow.ARG_BUCKET_ID)
        Assert.assertEquals("from_queue", Screen.DispenseFlow.ARG_FROM_QUEUE)
        Assert.assertEquals("allowed_ndcs", Screen.DispenseFlow.ARG_ALLOWED_NDCS)
        Assert.assertEquals(7, Screen.DispenseFlow.navArguments.size)
    }

    @Test
    fun `DispenseFlow createRoute with defaults`() {
        Assert.assertEquals(
            "dispense_flow/FIXED?from_hl7=false&from_resume=false&batch_id=0&bucket_id=&from_queue=false&allowed_ndcs=",
            Screen.DispenseFlow.createRoute("FIXED")
        )
    }

    @Test
    fun `DispenseFlow createRoute with all args`() {
        Assert.assertEquals(
            "dispense_flow/REGULAR?from_hl7=true&from_resume=true&batch_id=12&bucket_id=BKT&from_queue=true&allowed_ndcs=n1,n2",
            Screen.DispenseFlow.createRoute(
                scanType = "REGULAR",
                fromHl7 = true,
                fromResume = true,
                batchId = 12L,
                bucketId = "BKT",
                fromQueue = true,
                allowedNdcs = linkedSetOf("n1", "n2"),
            )
        )
    }

    @Test
    fun `DispenseFlow createRoute null bucket and empty ndcs`() {
        val route = Screen.DispenseFlow.createRoute(
            scanType = "FIXED",
            bucketId = null,
            allowedNdcs = emptySet(),
        )
        Assert.assertTrue(route.contains("bucket_id=&"))
        Assert.assertTrue(route.endsWith("allowed_ndcs="))
    }
}