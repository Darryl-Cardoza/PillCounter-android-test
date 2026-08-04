package com.rite.pillcounting.core.utils.preference

import android.content.Context
import android.util.Log
import com.rite.pillcounting.feature.dashboard.domain.model.Terminal
import com.rite.pillcounting.feature.hl7.util.Hl7Format
import com.rite.pillcounting.feature.settings.domain.model.ColorSettings
import com.rite.pillcounting.feature.settings.domain.model.ThemeColors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PreferenceHelper].
 *
 * [PreferenceHelper] instantiates [SecurePreferences] internally in its constructor, so we
 * intercept that construction with [mockkConstructor] and back it with an in-memory fake map
 * (mirroring SecurePreferences' own put/get contract) rather than exercising real AES/Keystore
 * code, which is unavailable on the plain JVM. android.util.Log is stubbed statically because
 * AppLogger delegates to it and the Android stub JAR throws on unmocked calls.
 */
class PreferenceHelperTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var helper: PreferenceHelper

    // Simple in-memory backing store standing in for the encrypted SharedPreferences.
    private val store = mutableMapOf<String, Any?>()

    @Before
    fun setup() {
        store.clear()
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.v(any(), any<String>()) } returns 0

        mockkConstructor(SecurePreferences::class)

        val stringKey = slot<String>()
        val stringVal = slot<String?>()
        every { anyConstructed<SecurePreferences>().putString(capture(stringKey), captureNullable(stringVal)) } answers {
            if (stringVal.isCaptured && stringVal.captured == null) store.remove(stringKey.captured) else store[stringKey.captured] = stringVal.captured
        }
        val getKey = slot<String>()
        every { anyConstructed<SecurePreferences>().getString(capture(getKey), any()) } answers {
            (store[getKey.captured] as? String) ?: secondArg()
        }

        val boolKey = slot<String>()
        val boolVal = slot<Boolean>()
        every { anyConstructed<SecurePreferences>().putBoolean(capture(boolKey), capture(boolVal)) } answers {
            store[boolKey.captured] = boolVal.captured
        }
        val getBoolKey = slot<String>()
        every { anyConstructed<SecurePreferences>().getBoolean(capture(getBoolKey), any()) } answers {
            (store[getBoolKey.captured] as? Boolean) ?: secondArg()
        }

        val intKey = slot<String>()
        val intVal = slot<Int>()
        every { anyConstructed<SecurePreferences>().putInt(capture(intKey), capture(intVal)) } answers {
            store[intKey.captured] = intVal.captured
        }
        val getIntKey = slot<String>()
        every { anyConstructed<SecurePreferences>().getInt(capture(getIntKey), any()) } answers {
            (store[getIntKey.captured] as? Int) ?: secondArg()
        }

        val longKey = slot<String>()
        val longVal = slot<Long>()
        every { anyConstructed<SecurePreferences>().putLong(capture(longKey), capture(longVal)) } answers {
            store[longKey.captured] = longVal.captured
        }
        val getLongKey = slot<String>()
        every { anyConstructed<SecurePreferences>().getLong(capture(getLongKey), any()) } answers {
            (store[getLongKey.captured] as? Long) ?: secondArg()
        }

        val removeKey = slot<String>()
        every { anyConstructed<SecurePreferences>().remove(capture(removeKey)) } answers {
            store.remove(removeKey.captured)
        }

        helper = PreferenceHelper(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ─────────────────────────── AUTH TOKENS ───────────────────────────

    @Test
    fun `saveTokens then getters return saved values`() {
        helper.saveTokens("access-123", "refresh-456")
        assertEquals("access-123", helper.getAccessToken())
        assertEquals("refresh-456", helper.getRefreshToken())
    }

    @Test
    fun `getAccessToken returns null when never set`() {
        assertNull(helper.getAccessToken())
        assertNull(helper.getRefreshToken())
    }

    @Test
    fun `clearTokens removes both tokens`() {
        helper.saveTokens("a", "b")
        helper.clearTokens()
        assertNull(helper.getAccessToken())
        assertNull(helper.getRefreshToken())
    }

    @Test
    fun `isStandaloneMode defaults to false`() {
        assertFalse(helper.isStandaloneMode())
    }

    @Test
    fun `setStandaloneMode persists the flag`() {
        helper.setStandaloneMode(true)
        assertTrue(helper.isStandaloneMode())
        helper.setStandaloneMode(false)
        assertFalse(helper.isStandaloneMode())
    }

    @Test
    fun `clearTokens resets standalone mode so the next user doesn't inherit it`() {
        helper.setStandaloneMode(true)
        helper.clearTokens()
        assertFalse(helper.isStandaloneMode())
    }

    @Test
    fun `saveTokens handles empty strings`() {
        helper.saveTokens("", "")
        assertEquals("", helper.getAccessToken())
        assertEquals("", helper.getRefreshToken())
    }

    // ─────────────────────────── USER SESSION ───────────────────────────

    @Test
    fun `isUserLoggedIn defaults to false`() {
        assertFalse(helper.isUserLoggedIn())
    }

    @Test
    fun `setUserLoggedIn true then isUserLoggedIn returns true`() {
        helper.setUserLoggedIn(true)
        assertTrue(helper.isUserLoggedIn())
    }

    @Test
    fun `setUserLoggedIn false then isUserLoggedIn returns false`() {
        helper.setUserLoggedIn(true)
        helper.setUserLoggedIn(false)
        assertFalse(helper.isUserLoggedIn())
    }

    // ─────────────────────────── USER IDENTIFIERS ───────────────────────────

    @Test
    fun `saveUserId then getUserId returns value`() {
        helper.saveUserId("user-1")
        assertEquals("user-1", helper.getUserId())
    }

    @Test
    fun `getUserId returns null when unset`() {
        assertNull(helper.getUserId())
    }

    @Test
    fun `saveLocalId then getLocalId returns value`() {
        helper.saveLocalId(42L)
        assertEquals(42L, helper.getLocalId())
    }

    @Test
    fun `getLocalId defaults to zero when unset`() {
        assertEquals(0L, helper.getLocalId())
    }

    @Test
    fun `isAllowLocalStorage defaults to true`() {
        assertTrue(helper.isAllowLocalStorage())
    }

    @Test
    fun `setAllowLocalStorage false then isAllowLocalStorage returns false`() {
        helper.setAllowLocalStorage(false)
        assertFalse(helper.isAllowLocalStorage())
    }

    // ─────────────────────────── TRANSACTIONS ───────────────────────────

    @Test
    fun `saveTxnId then getTxnId returns value`() {
        helper.saveTxnId(999L)
        assertEquals(999L, helper.getTxnId())
    }

    @Test
    fun `getTxnId defaults to zero`() {
        assertEquals(0L, helper.getTxnId())
    }

    // ─────────────────────────── THEME CACHING ───────────────────────────

    private fun sampleTheme() = ColorSettings(
        light = ThemeColors("p", "s", "t", "pb", "sb", "tc", "ib", "scp", "scs"),
        dark = ThemeColors("p2", "s2", "t2", "pb2", "sb2", "tc2", "ib2", "scp2", "scs2")
    )

    @Test
    fun `saveThemeColors then getThemeColors round-trips via gson`() {
        val theme = sampleTheme()
        helper.saveThemeColors(theme)
        val result = helper.getThemeColors()
        assertEquals(theme, result)
    }

    @Test
    fun `getThemeColors returns null when nothing cached`() {
        assertNull(helper.getThemeColors())
    }

    // ─────────────────────────── USER SETTINGS ───────────────────────────

    @Test
    fun `isDoNotAskAgain defaults to false`() {
        assertFalse(helper.isDoNotAskAgain())
    }

    @Test
    fun `saveDoNotAskAgain true is retrievable`() {
        helper.saveDoNotAskAgain(true)
        assertTrue(helper.isDoNotAskAgain())
    }

    @Test
    fun `isProfileChecked defaults to false and reflects set value`() {
        assertFalse(helper.isProfileChecked())
        helper.setProfileChecked(true)
        assertTrue(helper.isProfileChecked())
    }

    @Test
    fun `getContext returns the injected context`() {
        assertEquals(context, helper.getContext())
    }

    // ─────────────────────────── UI DIALOG FLAGS ───────────────────────────

    @Test
    fun `getShowNotesDialogSetting defaults to true`() {
        assertTrue(helper.getShowNotesDialogSetting())
    }

    @Test
    fun `saveShowNotesDialogSetting false is retrievable`() {
        helper.saveShowNotesDialogSetting(false)
        assertFalse(helper.getShowNotesDialogSetting())
    }

    // ─────────────────────────── RECENT LOGINS ───────────────────────────

    @Test
    fun `getRecentLogins returns empty list when unset`() {
        assertEquals(emptyList<String>(), helper.getRecentLogins())
    }

    @Test
    fun `addRecentLogin inserts newest first`() {
        helper.addRecentLogin("a@x.com")
        helper.addRecentLogin("b@x.com")
        assertEquals(listOf("b@x.com", "a@x.com"), helper.getRecentLogins())
    }

    @Test
    fun `addRecentLogin moves duplicate to front instead of duplicating`() {
        helper.addRecentLogin("a@x.com")
        helper.addRecentLogin("b@x.com")
        helper.addRecentLogin("a@x.com")
        assertEquals(listOf("a@x.com", "b@x.com"), helper.getRecentLogins())
    }

    @Test
    fun `addRecentLogin caps list at five entries`() {
        for (i in 1..6) helper.addRecentLogin("user$i@x.com")
        val logins = helper.getRecentLogins()
        assertEquals(5, logins.size)
        assertEquals(listOf("user6@x.com", "user5@x.com", "user4@x.com", "user3@x.com", "user2@x.com"), logins)
        assertFalse(logins.contains("user1@x.com"))
    }

    @Test
    fun `removeRecentLogin removes matching email only`() {
        helper.addRecentLogin("a@x.com")
        helper.addRecentLogin("b@x.com")
        helper.removeRecentLogin("a@x.com")
        assertEquals(listOf("b@x.com"), helper.getRecentLogins())
    }

    @Test
    fun `removeRecentLogin on empty list is a no-op`() {
        helper.removeRecentLogin("nobody@x.com")
        assertEquals(emptyList<String>(), helper.getRecentLogins())
    }

    // ─────────────────────────── HISTORY RETENTION ───────────────────────────

    @Test
    fun `getHistoryRetention defaults to seven days`() {
        assertEquals(7, helper.getHistoryRetention())
    }

    @Test
    fun `saveHistoryRetention then getHistoryRetention returns value`() {
        helper.saveHistoryRetention(30)
        assertEquals(30, helper.getHistoryRetention())
    }

    @Test
    fun `saveHistoryRetention accepts zero boundary`() {
        helper.saveHistoryRetention(0)
        assertEquals(0, helper.getHistoryRetention())
    }

    // ─────────────────────────── HL7 MESSAGE TRACKING ───────────────────────────

    @Test
    fun `getSentMessageTxnId defaults to negative one`() {
        assertEquals(-1L, helper.getSentMessageTxnId())
    }

    @Test
    fun `saveSentMessageTxnId then getSentMessageTxnId returns value`() {
        helper.saveSentMessageTxnId(555L)
        assertEquals(555L, helper.getSentMessageTxnId())
    }

    // ─────────────────────────── NSD / HL7 SETTINGS ───────────────────────────

    @Test
    fun `getNsdBroadcastType defaults to empty string`() {
        assertEquals("", helper.getNsdBroadcastType())
    }

    @Test
    fun `saveNsdBroadcastType then getNsdBroadcastType returns value`() {
        helper.saveNsdBroadcastType("_pillcounter._tcp")
        assertEquals("_pillcounter._tcp", helper.getNsdBroadcastType())
    }

    @Test
    fun `getNsdDiscoveryType defaults to empty string`() {
        assertEquals("", helper.getNsdDiscoveryType())
    }

    @Test
    fun `saveNsdDiscoveryType then getNsdDiscoveryType returns value`() {
        helper.saveNsdDiscoveryType("_pms._tcp")
        assertEquals("_pms._tcp", helper.getNsdDiscoveryType())
    }

    @Test
    fun `isHl7Enabled defaults to true`() {
        assertTrue(helper.isHl7Enabled())
    }

    @Test
    fun `setHl7Enabled false is retrievable`() {
        helper.setHl7Enabled(false)
        assertFalse(helper.isHl7Enabled())
    }

    @Test
    fun `isSoundEnabled defaults to true`() {
        assertTrue(helper.isSoundEnabled())
    }

    @Test
    fun `setSoundEnabled false is retrievable`() {
        helper.setSoundEnabled(false)
        assertFalse(helper.isSoundEnabled())
    }

    @Test
    fun `isHapticEnabled defaults to true`() {
        assertTrue(helper.isHapticEnabled())
    }

    @Test
    fun `setHapticEnabled false is retrievable`() {
        helper.setHapticEnabled(false)
        assertFalse(helper.isHapticEnabled())
    }

    @Test
    fun `isRequireBackCountEnabled defaults to true`() {
        assertTrue(helper.isRequireBackCountEnabled())
    }

    @Test
    fun `setRequireBackCountEnabled false is retrievable`() {
        helper.setRequireBackCountEnabled(false)
        assertFalse(helper.isRequireBackCountEnabled())
    }

    @Test
    fun `isRequireDoubleCountEnabled defaults to true`() {
        assertTrue(helper.isRequireDoubleCountEnabled())
    }

    @Test
    fun `setRequireDoubleCountEnabled false is retrievable`() {
        helper.setRequireDoubleCountEnabled(false)
        assertFalse(helper.isRequireDoubleCountEnabled())
    }

    // ─────────────────────────── CONTROL DRUG TYPES ───────────────────────────

    @Test
    fun `getControlDrugTypes returns empty set when unset`() {
        assertEquals(emptySet<String>(), helper.getControlDrugTypes())
    }

    @Test
    fun `isControlDrugTypesInitialized defaults to false`() {
        assertFalse(helper.isControlDrugTypesInitialized())
    }

    @Test
    fun `setControlDrugTypes stores set and marks initialized`() {
        helper.setControlDrugTypes(setOf("C2", "C3"))
        assertEquals(setOf("C2", "C3"), helper.getControlDrugTypes())
        assertTrue(helper.isControlDrugTypesInitialized())
    }

    @Test
    fun `setControlDrugTypes with empty set still marks initialized`() {
        helper.setControlDrugTypes(emptySet())
        assertEquals(emptySet<String>(), helper.getControlDrugTypes())
        assertTrue(helper.isControlDrugTypesInitialized())
    }

    @Test
    fun `isSoundOverride defaults to true`() {
        assertTrue(helper.isSoundOverride())
    }

    @Test
    fun `setSoundOverride false is retrievable`() {
        helper.setSoundOverride(false)
        assertFalse(helper.isSoundOverride())
    }

    // ─────────────────────────── BARCODE REGEX ───────────────────────────

    @Test
    fun `getBarcodeRegex returns null when unset`() {
        assertNull(helper.getBarcodeRegex())
    }

    @Test
    fun `saveBarcodeRegex then getBarcodeRegex returns value`() {
        helper.saveBarcodeRegex("^[0-9]{10}$")
        assertEquals("^[0-9]{10}$", helper.getBarcodeRegex())
    }

    // ─────────────────────────── BUCKET LIST ───────────────────────────

    @Test
    fun `getBucketList returns empty list when unset`() {
        assertEquals(emptyList<String>(), helper.getBucketList())
    }

    @Test
    fun `setKeyBucketList then getBucketList returns saved list`() {
        helper.setKeyBucketList(listOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C"), helper.getBucketList())
    }

    @Test
    fun `setKeyBucketList with empty list returns empty list`() {
        helper.setKeyBucketList(emptyList())
        assertEquals(emptyList<String>(), helper.getBucketList())
    }

    // ─────────────────────────── HL7 CONFIG ───────────────────────────

    @Test
    fun `getHl7PmsHost and getHl7PillCounterHost default to empty string`() {
        assertEquals("", helper.getHl7PmsHost())
        assertEquals("", helper.getHl7PillCounterHost())
    }

    @Test
    fun `saveHl7Config saves both hosts and marks fetched`() {
        helper.saveHl7Config("pms.local", "counter.local")
        assertEquals("pms.local", helper.getHl7PmsHost())
        assertEquals("counter.local", helper.getHl7PillCounterHost())
        assertTrue(helper.isHl7ConfigFetched())
    }

    @Test
    fun `isHl7ConfigFetched defaults to false`() {
        assertFalse(helper.isHl7ConfigFetched())
    }

    @Test
    fun `clearHl7Config removes hosts and unsets fetched flag`() {
        helper.saveHl7Config("pms.local", "counter.local")
        helper.clearHl7Config()
        assertEquals("", helper.getHl7PmsHost())
        assertEquals("", helper.getHl7PillCounterHost())
        assertFalse(helper.isHl7ConfigFetched())
    }

    // ─────────────────────────── TERMINALS ───────────────────────────

    @Test
    fun `getTerminals returns empty list when unset`() {
        assertEquals(emptyList<Terminal>(), helper.getTerminals())
    }

    @Test
    fun `saveTerminals then getTerminals round-trips list`() {
        val terminals = listOf(
            Terminal("t1", "Front", true, "2024-01-01", "2024-01-02"),
            Terminal("t2", "Back", false, null, null)
        )
        helper.saveTerminals(terminals)
        assertEquals(terminals, helper.getTerminals())
    }

    @Test
    fun `saveSelectedTerminalId then getSelectedTerminalId returns value`() {
        helper.saveSelectedTerminalId("t42")
        assertEquals("t42", helper.getSelectedTerminalId())
    }

    @Test
    fun `getSelectedTerminalId returns null when unset`() {
        assertNull(helper.getSelectedTerminalId())
    }

    @Test
    fun `saveSelectedTerminalName then getSelectedTerminalName returns value`() {
        helper.saveSelectedTerminalName("Front Counter")
        assertEquals("Front Counter", helper.getSelectedTerminalName())
    }

    @Test
    fun `getSelectedTerminalName returns null when unset`() {
        assertNull(helper.getSelectedTerminalName())
    }

    @Test
    fun `savePharmacyType then getPharmacyType returns value`() {
        helper.savePharmacyType("retail")
        assertEquals("retail", helper.getPharmacyType())
    }

    @Test
    fun `getPharmacyType returns null when unset`() {
        assertNull(helper.getPharmacyType())
    }

    // ─────────────────────────── HAZARDOUS DRUG ───────────────────────────

    @Test
    fun `isHazardousDrugEnabled defaults to true`() {
        assertTrue(helper.isHazardousDrugEnabled())
    }

    @Test
    fun `setHazardousDrugEnabled false is retrievable`() {
        helper.setHazardousDrugEnabled(false)
        assertFalse(helper.isHazardousDrugEnabled())
    }

    // ─────────────────────────── TRAY COLOR CLASSIFICATION ───────────────────────────

    @Test
    fun `getHazardousTrayColor returns null when unset`() {
        assertNull(helper.getHazardousTrayColor())
    }

    @Test
    fun `setHazardousTrayColor then getHazardousTrayColor returns value`() {
        helper.setHazardousTrayColor("Red")
        assertEquals("Red", helper.getHazardousTrayColor())
    }

    @Test
    fun `clearAllTrayColorLists removes stored tray color`() {
        helper.setHazardousTrayColor("Red")
        helper.clearAllTrayColorLists()
        assertNull(helper.getHazardousTrayColor())
    }

    // ─────────────────────────── HL7 VERSION ───────────────────────────

    @Test
    fun `getHl7Version defaults to DEFAULT_HL7_VERSION constant`() {
        assertEquals(PreferenceHelper.DEFAULT_HL7_VERSION, helper.getHl7Version())
        assertEquals("2.5.1", helper.getHl7Version())
    }

    @Test
    fun `saveHl7Version then getHl7Version returns saved value`() {
        helper.saveHl7Version("2.3")
        assertEquals("2.3", helper.getHl7Version())
    }

    // ─────────────────────────── HL7 TLS BYPASS ───────────────────────────

    @Test
    fun `isBypassTlsEnabled defaults to true`() {
        assertTrue(helper.isBypassTlsEnabled())
    }

    @Test
    fun `setBypassTlsEnabled false is retrievable`() {
        helper.setBypassTlsEnabled(false)
        assertFalse(helper.isBypassTlsEnabled())
    }

    // ─────────────────────────── HL7 FORMAT ───────────────────────────

    @Test
    fun `getHl7Format defaults to Hl7Format DEFAULT when unset`() {
        assertEquals(Hl7Format.DEFAULT, helper.getHl7Format())
    }

    @Test
    fun `saveHl7Format then getHl7Format returns saved enum value`() {
        helper.saveHl7Format(Hl7Format.VIVID)
        assertEquals(Hl7Format.VIVID, helper.getHl7Format())
    }

    @Test
    fun `saveHl7Format persists EYECON and DISPENSESURE distinctly`() {
        helper.saveHl7Format(Hl7Format.EYECON)
        assertEquals(Hl7Format.EYECON, helper.getHl7Format())

        helper.saveHl7Format(Hl7Format.DISPENSESURE)
        assertEquals(Hl7Format.DISPENSESURE, helper.getHl7Format())
    }

    @Test
    fun `getHl7Format falls back to DEFAULT for corrupted unknown enum name`() {
        helper.saveHl7Format(Hl7Format.VIVID)
        // Corrupt the stored value directly to simulate an unparsable/legacy value.
        store["key_hl7_format"] = "NOT_A_REAL_FORMAT"
        assertEquals(Hl7Format.DEFAULT, helper.getHl7Format())
    }

    // ─────────────────────────── CORRUPTED JSON (UNGUARDED gson.fromJson CALLS) ───────────────────────────
    // Unlike getHl7Format, these getters don't wrap gson.fromJson in a try/catch, so malformed
    // stored JSON is expected to propagate as a JsonSyntaxException rather than fail silently.

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `getThemeColors throws on corrupted json`() {
        store["theme_colors"] = "not-json"
        helper.getThemeColors()
    }

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `getRecentLogins throws on corrupted json`() {
        store["recent_logins"] = "not-json"
        helper.getRecentLogins()
    }

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `getControlDrugTypes throws on corrupted json`() {
        store["key_control_drug_types"] = "not-json"
        helper.getControlDrugTypes()
    }

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `getBucketList throws on corrupted json`() {
        store["key_bucket_list"] = "not-json"
        helper.getBucketList()
    }

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `getTerminals throws on corrupted json`() {
        store["key_terminals"] = "not-json"
        helper.getTerminals()
    }

    // ─────────────────────────── ADDITIONAL EDGE CASES ───────────────────────────

    @Test
    fun `addRecentLogin on empty history adds a single entry`() {
        helper.addRecentLogin("only@x.com")
        assertEquals(listOf("only@x.com"), helper.getRecentLogins())
    }

    @Test
    fun `addRecentLogin re-adding the most recent email keeps it at front and does not grow list`() {
        helper.addRecentLogin("a@x.com")
        helper.addRecentLogin("a@x.com")
        assertEquals(listOf("a@x.com"), helper.getRecentLogins())
    }

    @Test
    fun `setControlDrugTypes overwrites previous set entirely rather than merging`() {
        helper.setControlDrugTypes(setOf("C2"))
        helper.setControlDrugTypes(setOf("C4", "C5"))
        assertEquals(setOf("C4", "C5"), helper.getControlDrugTypes())
    }

    @Test
    fun `setKeyBucketList overwrites previous list entirely rather than merging`() {
        helper.setKeyBucketList(listOf("A"))
        helper.setKeyBucketList(listOf("X", "Y"))
        assertEquals(listOf("X", "Y"), helper.getBucketList())
    }

    @Test
    fun `saveTerminals with empty list round-trips to empty list`() {
        helper.saveTerminals(emptyList())
        assertEquals(emptyList<Terminal>(), helper.getTerminals())
    }

    @Test
    fun `saveThemeColors overwrites previously cached theme`() {
        helper.saveThemeColors(sampleTheme())
        val second = ColorSettings(
            light = ThemeColors("p3", "s3", "t3", "pb3", "sb3", "tc3", "ib3", "scp3", "scs3"),
            dark = ThemeColors("p4", "s4", "t4", "pb4", "sb4", "tc4", "ib4", "scp4", "scs4")
        )
        helper.saveThemeColors(second)
        assertEquals(second, helper.getThemeColors())
    }

    @Test
    fun `saveTokens overwrites previously saved tokens`() {
        helper.saveTokens("old-access", "old-refresh")
        helper.saveTokens("new-access", "new-refresh")
        assertEquals("new-access", helper.getAccessToken())
        assertEquals("new-refresh", helper.getRefreshToken())
    }

    @Test
    fun `saveLocalId overwrites previous value`() {
        helper.saveLocalId(1L)
        helper.saveLocalId(2L)
        assertEquals(2L, helper.getLocalId())
    }

    @Test
    fun `saveHistoryRetention accepts negative value as-is`() {
        helper.saveHistoryRetention(-1)
        assertEquals(-1, helper.getHistoryRetention())
    }

    @Test
    fun `saveHl7Version overwrites previous version`() {
        helper.saveHl7Version("2.3")
        helper.saveHl7Version("2.5")
        assertEquals("2.5", helper.getHl7Version())
    }

    @Test
    fun `saveSelectedTerminalId overwrites previous id`() {
        helper.saveSelectedTerminalId("t1")
        helper.saveSelectedTerminalId("t2")
        assertEquals("t2", helper.getSelectedTerminalId())
    }

    @Test
    fun `savePharmacyType overwrites previous value`() {
        helper.savePharmacyType("retail")
        helper.savePharmacyType("hospital")
        assertEquals("hospital", helper.getPharmacyType())
    }

    @Test
    fun `setHazardousTrayColor overwrites previous color`() {
        helper.setHazardousTrayColor("Red")
        helper.setHazardousTrayColor("Blue")
        assertEquals("Blue", helper.getHazardousTrayColor())
    }

    @Test
    fun `saveNsdBroadcastType and saveNsdDiscoveryType do not interfere with each other`() {
        helper.saveNsdBroadcastType("_broadcast._tcp")
        helper.saveNsdDiscoveryType("_discovery._tcp")
        assertEquals("_broadcast._tcp", helper.getNsdBroadcastType())
        assertEquals("_discovery._tcp", helper.getNsdDiscoveryType())
    }
}
