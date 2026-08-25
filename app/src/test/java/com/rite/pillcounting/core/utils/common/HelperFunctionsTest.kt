package com.rite.pillcounting.core.utils.common

import com.rite.pillcounting.core.room.models.dtos.StatusTypeCount
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the PURE members of [HelperFunctions]:
 *  - maskEmail (all branches: null/blank, no/edge @, normal masking)
 *  - resolveStartDestinationAndClearIfExpired (logged-in vs not, threshold branches)
 *  - mapCounts (all CountType x CountStatus combinations incl. else branch)
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
    fun resolve_loggedIn_noHealthOrLoginAnchor_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns 0L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_healthAnchorWithinThreshold_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 5_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_healthAnchorBeyondThreshold_clearsTokensAndReturnsAuth() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.getLastHealthCheckedAt() } returns System.currentTimeMillis() - 120_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        val result = HelperFunctions.resolveStartDestinationAndClearIfExpired(pref)
        assertEquals(AUTH_GRAPH_ROUTE, result)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.clearLoggedInAt() }
    }

    @Test
    fun resolve_loggedIn_noHealthButLoginAnchorWithinThreshold_returnsDashboard() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns System.currentTimeMillis() - 10_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        assertEquals(Screen.Dashboard.route, HelperFunctions.resolveStartDestinationAndClearIfExpired(pref))
    }

    @Test
    fun resolve_loggedIn_noHealthAndLoginAnchorBeyondThreshold_clearsTokensAndReturnsAuth() {
        val pref = mockk<PreferenceHelper>(relaxed = true)
        every { pref.isUserLoggedIn() } returns true
        every { pref.getLastHealthCheckedAt() } returns 0L
        every { pref.getLoggedInAt() } returns System.currentTimeMillis() - 120_000L
        every { pref.getOfflineSessionThresholdSeconds() } returns 60L
        val result = HelperFunctions.resolveStartDestinationAndClearIfExpired(pref)
        assertEquals(AUTH_GRAPH_ROUTE, result)
        io.mockk.verify { pref.clearTokens() }
        io.mockk.verify { pref.setUserLoggedIn(false) }
        io.mockk.verify { pref.clearLoggedInAt() }
    }

    // ───────────────────────────── mapCounts ─────────────────────────────

    @Test
    fun mapCounts_emptyRows_returnsZeros() {
        val result = HelperFunctions.mapCounts(emptyList())
        assertEquals(0, result.fixedCompleted)
        assertEquals(0, result.fixedPartial)
        assertEquals(0, result.regularCompleted)
        assertEquals(0, result.regularPartial)
    }

    @Test
    fun mapCounts_allCombinations_areMapped() {
        val rows = listOf(
            StatusTypeCount(CountStatus.COMPLETED, true, 1),
            StatusTypeCount(CountStatus.PARTIAL, true, 2),
            StatusTypeCount(CountStatus.COMPLETED, false, 3),
            StatusTypeCount(CountStatus.PARTIAL, false, 4)
        )
        val result = HelperFunctions.mapCounts(rows)
        assertEquals(1, result.fixedCompleted)
        assertEquals(2, result.fixedPartial)
        assertEquals(3, result.regularCompleted)
        assertEquals(4, result.regularPartial)
    }

    @Test
    fun mapCounts_otherStatus_hitsElseBranch_andIsIgnored() {
        // FORCE_COMPLETED falls through the inner else -> {} for both count types
        val rows = listOf(
            StatusTypeCount(CountStatus.FORCE_COMPLETED, true, 99),
            StatusTypeCount(CountStatus.FORCE_COMPLETED, false, 88)
        )
        val result = HelperFunctions.mapCounts(rows)
        assertEquals(0, result.fixedCompleted)
        assertEquals(0, result.fixedPartial)
        assertEquals(0, result.regularCompleted)
        assertEquals(0, result.regularPartial)
    }

}
