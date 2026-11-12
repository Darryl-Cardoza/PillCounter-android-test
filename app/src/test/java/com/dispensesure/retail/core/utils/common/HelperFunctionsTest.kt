package com.dispensesure.retail.core.utils.common

import com.dispensesure.retail.core.audit.model.SessionEndReason
import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.navigation.AUTH_GRAPH_ROUTE
import com.dispensesure.retail.navigation.Screen
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the PURE members of [HelperFunctions]:
 *  - maskEmail (all branches: null/blank, no/edge @, normal masking)
 *  - resolveStartDestinationAndClearIfExpired (logged-in vs not, threshold branches)
 *  - clearSessionIfNotRemembered (remember on/off, logged out)
 *
 * Android-dependent members (exitApp, enableImmersiveFullscreen, openPlayStore,
 * saveBitmapToFile, toGrayscaleBitmap) are NOT tested here — see the report's skip list.
 */
class HelperFunctionsTest {

    // ───────────────────────────── maskEmail ─────────────────────────────

    @Test
    fun maskEmail_null_returnsEmpty() {
        assertEquals("", HelperFunctions.maskEmail(null))
    }

    @Test
    fun maskEmail_blank_returnsEmpty() {
        assertEquals("", HelperFunctions.maskEmail("   "))
    }

    @Test
    fun maskEmail_normal_masksLocalPart() {
        // "andrew@example.com" -> visible "an" + stars + "@example.com"
        val result = HelperFunctions.maskEmail("andrew@example.com")
        assertEquals("an****@example.com", result)
    }

    @Test
    fun maskEmail_shortLocalPart_usesMinStars() {
        // local "ab" -> visible "ab", starsCount = max(3, (2-2)=0 coerced to 3) = 3
        assertEquals("ab***@x.com", HelperFunctions.maskEmail("ab@x.com"))
    }

    @Test
    fun maskEmail_noAtSign_shortString_returnedAsIs() {
        // atIndex <= 0, length <= showFirst(2) -> returned trimmed
        assertEquals("a", HelperFunctions.maskEmail("a"))
    }

    @Test
    fun maskEmail_noAtSign_longString_takeFirstPlusStars() {
        // atIndex <= 0, length > showFirst -> take(2)+"***"
        assertEquals("ab***", HelperFunctions.maskEmail("abcdef"))
    }

    @Test
    fun maskEmail_atSignAtEnd_treatedAsNoLocalSplit() {
        // atIndex == length-1 -> same branch as "no @"; "user@" length 5 > 2
        assertEquals("us***", HelperFunctions.maskEmail("user@"))
    }

    @Test
    fun maskEmail_atSignAtStart_treatedAsNoLocalSplit() {
        // atIndex == 0 -> first branch; "@domain" length 7 > 2 -> "@d***"? no: take(2) of "@domain"="@d"
        assertEquals("@d***", HelperFunctions.maskEmail("@domain"))
    }

    @Test
    fun maskEmail_customShowFirstAndMinStars() {
        val result = HelperFunctions.maskEmail("johndoe@mail.com", showFirst = 3, minStars = 2)
        assertEquals("joh****@mail.com", result)
    }

    @Test
    fun maskEmail_localShorterThanShowFirst_visibleClamped() {
        // local "a", showFirst 5 -> visible take(min(5,1))="a", stars=max(3,...)=3
        assertEquals("a***@b.com", HelperFunctions.maskEmail("a@b.com", showFirst = 5))
    }

    // ───────────────────────────── resolveStartDestinationAndClearIfExpired ─────────────────────────────

    @Test
    fun resolve_notLoggedIn_returnsAuthGraph() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns false
        assertEquals(AUTH_GRAPH_ROUTE, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_noRegionStored_clearsSessionAndReturnsAuth() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns null
        val result = HelperFunctions.resolveStartDestinationAndClearIfExpired(pref)
        assertEquals(AUTH_GRAPH_ROUTE, result)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.saveLocalId(0) }
    }

    @Test
    fun resolve_loggedIn_noHealthOrLoginAnchor_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns 0L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_healthAnchorWithinThreshold_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 5_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_healthAnchorBeyondThreshold_clearsTokensAndReturnsAuth() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 120_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        val result = HelperFunctions.resolveStartDestinationAndClearIfExpired(pref)
        assertEquals(AUTH_GRAPH_ROUTE, result)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.clearLoggedInAt() }
        io.mockk.verify { pref.saveLocalId(0) }
        io.mockk.verify { pref.clearHl7Config() }
    }

    @Test
    fun resolve_loggedIn_noHealthButLoginAnchorWithinThreshold_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns System.currentTimeMillis() - 10_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_noHealthAndLoginAnchorBeyondThreshold_clearsTokensAndReturnsAuth() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns System.currentTimeMillis() - 120_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        val result = HelperFunctions.resolveStartDestinationAndClearIfExpired(pref)
        assertEquals(AUTH_GRAPH_ROUTE, result)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.clearLoggedInAt() }
        io.mockk.verify { pref.saveLocalId(0) }
        io.mockk.verify { pref.clearHl7Config() }
    }

    // ───────────────────────────── clearSessionIfNotRemembered ─────────────────────────────

    @Test
    fun clearIfNotRemembered_loggedInRememberOff_clearsSession() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.isRememberMeEnabled() } returns false
        HelperFunctions.clearSessionIfNotRemembered(pref)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.clearLoggedInAt() }
        io.mockk.verify { pref.saveLocalId(0) }
        io.mockk.verify { pref.clearHl7Config() }
    }

    @Test
    fun clearIfNotRemembered_loggedInRememberOn_keepsSession() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.isRememberMeEnabled() } returns true
        HelperFunctions.clearSessionIfNotRemembered(pref)
        io.mockk.verify(exactly = 0) { pref.clearTokens() }
        io.mockk.verify(exactly = 0) { pref.setUserLoggedIn(any()) }
        io.mockk.verify(exactly = 0) { pref.saveLocalId(any()) }
        io.mockk.verify(exactly = 0) { pref.clearHl7Config() }
    }

    @Test
    fun clearIfNotRemembered_notLoggedIn_doesNothing() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns false
        every { pref.isRememberMeEnabled() } returns false
        HelperFunctions.clearSessionIfNotRemembered(pref)
        io.mockk.verify(exactly = 0) { pref.clearTokens() }
        io.mockk.verify(exactly = 0) { pref.setUserLoggedIn(any()) }
    }

    @Test
    fun clearIfNotRemembered_reportsTheReasonOnlyWhenItClears() {
        val reasons = mutableListOf<SessionEndReason>()
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.isRememberMeEnabled() } returns false
        HelperFunctions.clearSessionIfNotRemembered(pref) { reasons += it }

        every { pref.isRememberMeEnabled() } returns true
        HelperFunctions.clearSessionIfNotRemembered(pref) { reasons += it }

        assertEquals(listOf(SessionEndReason.NOT_REMEMBERED_ON_LAUNCH), reasons)
    }

    @Test
    fun resolve_reportsTheReasonForEachClear() {
        val reasons = mutableListOf<SessionEndReason>()
        val noRegion = mockk<PreferenceHelper>(relaxed = true)
        every { noRegion.isUserLoggedIn() } returns true
        every { noRegion.getRegion() } returns null
        HelperFunctions.resolveStartDestinationAndClearIfExpired(noRegion) { reasons += it }

        val expired = mockk<PreferenceHelper>(relaxed = true)
        every { expired.isUserLoggedIn() } returns true
        every { expired.getRegion() } returns Region.US
        every { expired.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 120_000L
        every { expired.getOfflineSessionThresholdSeconds() } returns 60L
        HelperFunctions.resolveStartDestinationAndClearIfExpired(expired) { reasons += it }

        assertEquals(
            listOf(SessionEndReason.REGION_MISSING_ON_LAUNCH, SessionEndReason.EXPIRED_ON_LAUNCH),
            reasons
        )
    }

    @Test
    fun resolve_withinThreshold_reportsNothing() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getRegion() } returns Region.US
        every { pref.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 5_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        var reported = false
        HelperFunctions.resolveStartDestinationAndClearIfExpired(pref) { reported = true }
        assertEquals(false, reported)
    }

}
