package com.rite.pillcounting.core.utils.common

import com.rite.pillcounting.core.room.models.dtos.StatusTypeCount
import com.rite.pillcounting.core.room.models.enums.CountStatus
import com.rite.pillcounting.core.room.models.enums.CountType
import com.rite.pillcounting.core.security.models.SecureString
import com.rite.pillcounting.core.utils.common.HelperFunctions.plain
import com.rite.pillcounting.core.utils.common.HelperFunctions.secure
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.navigation.AUTH_GRAPH_ROUTE
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the PURE members of [HelperFunctions]:
 *  - maskEmail (all branches: null/blank, no/edge @, normal masking)
 *  - getStartDestination (logged-in vs not)
 *  - mapCounts (all CountType x CountStatus combinations incl. else branch)
 *  - String.secure() / SecureString?.plain() extensions
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

    // ───────────────────────────── getStartDestination ─────────────────────────────

    @Test
    fun getStartDestination_loggedIn_returnsDashboardRoute() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns true
        assertEquals(Screen.Dashboard.route, HelperFunctions.getStartDestination(pref))
    }

    @Test
    fun getStartDestination_notLoggedIn_returnsAuthGraph() {
        val pref = mockk<PreferenceHelper>()
        every { pref.isUserLoggedIn() } returns false
        assertEquals(AUTH_GRAPH_ROUTE, HelperFunctions.getStartDestination(pref))
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
            StatusTypeCount(CountStatus.COMPLETED, CountType.FIXED, 1),
            StatusTypeCount(CountStatus.PARTIAL, CountType.FIXED, 2),
            StatusTypeCount(CountStatus.COMPLETED, CountType.REGULAR, 3),
            StatusTypeCount(CountStatus.PARTIAL, CountType.REGULAR, 4)
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
            StatusTypeCount(CountStatus.FORCE_COMPLETED, CountType.FIXED, 99),
            StatusTypeCount(CountStatus.FORCE_COMPLETED, CountType.REGULAR, 88)
        )
        val result = HelperFunctions.mapCounts(rows)
        assertEquals(0, result.fixedCompleted)
        assertEquals(0, result.fixedPartial)
        assertEquals(0, result.regularCompleted)
        assertEquals(0, result.regularPartial)
    }

    // ───────────────────────────── secure / plain extensions ─────────────────────────────

    @Test
    fun stringSecure_wrapsValue() {
        val secured: SecureString = "topsecret".secure()
        assertEquals("topsecret", secured.value)
    }

    @Test
    fun secureStringPlain_unwrapsValue() {
        assertEquals("hello", SecureString("hello").plain())
    }

    @Test
    fun secureStringPlain_null_returnsNull() {
        val nullSecure: SecureString? = null
        assertNull(nullSecure.plain())
    }

    @Test
    fun secureStringPlain_wrappedNullValue_returnsNull() {
        assertNull(SecureString(null).plain())
    }
}
