package com.dispensesure.retail

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
        Assert.assertEquals("profile", Screen.Profile.route)
        Assert.assertEquals("unsynced_transaction_screen", Screen.UnsyncedTransactionScreen.route)
    }

    // ─────────────────────────── StockCount ───────────────────────────

    @Test
    fun `StockCount route createRoute and args`() {
        Assert.assertEquals(
            "stock_count?batch_id={batch_id}&bucket_id={bucket_id}",
            Screen.StockCount.route
        )
        Assert.assertEquals("batch_id", Screen.StockCount.ARG_BATCH_ID)
        Assert.assertEquals("bucket_id", Screen.StockCount.ARG_BUCKET_ID)
        Assert.assertEquals(
            "stock_count?batch_id=0&bucket_id=",
            Screen.StockCount.createRoute()
        )
        Assert.assertEquals(
            "stock_count?batch_id=7&bucket_id=B1",
            Screen.StockCount.createRoute(batchId = 7L, bucketId = "B1")
        )
        // null bucketId coerces to empty
        Assert.assertEquals(
            "stock_count?batch_id=7&bucket_id=",
            Screen.StockCount.createRoute(batchId = 7L, bucketId = null)
        )
        Assert.assertEquals(2, Screen.StockCount.navArguments.size)
    }

    // ─────────────────────────── BatchHistoryDetail ───────────────────────────

    @Test
    fun `BatchHistoryDetail route createRoute and args`() {
        Assert.assertEquals("batch_history_detail/{batch_id}", Screen.BatchHistoryDetail.route)
        Assert.assertEquals("batch_id", Screen.BatchHistoryDetail.ARG_BATCH_ID)
        Assert.assertEquals("batch_history_detail/9", Screen.BatchHistoryDetail.createRoute(9L))
        Assert.assertEquals(1, Screen.BatchHistoryDetail.navArguments.size)
    }

    // ─────────────────────────── HistoryDetail ───────────────────────────

    @Test
    fun `HistoryDetail route createRoute and args`() {
        Assert.assertEquals("history_detail/{txn_id}", Screen.HistoryDetail.route)
        Assert.assertEquals("txn_id", Screen.HistoryDetail.ARG_TXN_ID)
        Assert.assertEquals("history_detail/42", Screen.HistoryDetail.createRoute(42L))
        Assert.assertEquals(1, Screen.HistoryDetail.navArguments.size)
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

    // ─────────────────────────── DispenseFlow ───────────────────────────

    @Test
    fun `DispenseFlow route and arg constants`() {
        Assert.assertEquals(
            "dispense_flow?txn_id={txn_id}&from_hl7={from_hl7}&from_queue={from_queue}",
            Screen.DispenseFlow.route
        )
        Assert.assertEquals("txn_id", Screen.DispenseFlow.ARG_TXN_ID)
        Assert.assertEquals("from_hl7", Screen.DispenseFlow.ARG_FROM_HL7)
        Assert.assertEquals("from_queue", Screen.DispenseFlow.ARG_FROM_QUEUE)
        Assert.assertEquals(3, Screen.DispenseFlow.navArguments.size)
        Assert.assertEquals(
            0L,
            Screen.DispenseFlow.navArguments.first { it.name == Screen.DispenseFlow.ARG_TXN_ID }.argument.defaultValue
        )
    }

    @Test
    fun `DispenseFlow createRoute with defaults`() {
        Assert.assertEquals(
            "dispense_flow?txn_id=0&from_hl7=false&from_queue=false",
            Screen.DispenseFlow.createRoute()
        )
    }

    @Test
    fun `DispenseFlow createRoute with all args`() {
        Assert.assertEquals(
            "dispense_flow?txn_id=42&from_hl7=true&from_queue=true",
            Screen.DispenseFlow.createRoute(
                txnId = 42L,
                fromHl7 = true,
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
