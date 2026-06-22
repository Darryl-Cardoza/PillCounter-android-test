package com.rite.pillcounting.core.utils.common

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptionsBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the NavController extensions in NavigationExtensions.kt.
 *
 * NavController is mocked with mockk. The androidx `NavController.navigate(route) { }`
 * builder is itself a top-level extension function (NavControllerKt) so it is stubbed
 * via mockkStatic to avoid touching the real navigation graph. android.util.Log is
 * mocked statically so the debug logging inside popBackStackSafely is inert.
 *
 * Covers:
 *  - navigateSafely: already on route (no-op) vs different route (navigate called).
 *  - popBackStackSafely: no previous entry (false), successful pop (true),
 *    and debounce block on a rapid second call (false).
 */
class NavigationExtensionsTest {

    @After
    fun tearDown() = unmockkAll()

    private fun dest(route: String?): NavDestination =
        mockk { every { this@mockk.route } returns route }

    // The builder overload lives in androidx.navigation.NavControllerKt; it must be stubbed
    // on the concrete mock instance (a matcher cannot be used as an `every` receiver).
    private fun mockNavigateExtension(nav: NavController) {
        mockkStatic("androidx.navigation.NavControllerKt")
        every { nav.navigate(any<String>(), any<NavOptionsBuilder.() -> Unit>()) } returns Unit
    }

    @Test
    fun navigateSafely_sameRoute_doesNotNavigate() {
        val nav = mockk<NavController>(relaxed = true)
        mockNavigateExtension(nav)
        every { nav.currentDestination } returns dest("home")

        nav.navigateSafely("home")

        verify(exactly = 0) { nav.navigate(any<String>(), any<NavOptionsBuilder.() -> Unit>()) }
    }

    @Test
    fun navigateSafely_differentRoute_navigatesWithSingleTop() {
        val nav = mockk<NavController>(relaxed = true)
        mockNavigateExtension(nav)
        every { nav.currentDestination } returns dest("home")

        nav.navigateSafely("details")

        verify(exactly = 1) { nav.navigate("details", any<NavOptionsBuilder.() -> Unit>()) }
    }

    @Test
    fun navigateSafely_nullCurrentDestination_navigates() {
        val nav = mockk<NavController>(relaxed = true)
        mockNavigateExtension(nav)
        every { nav.currentDestination } returns null

        nav.navigateSafely("details")

        verify(exactly = 1) { nav.navigate("details", any<NavOptionsBuilder.() -> Unit>()) }
    }

    @Test
    fun popBackStackSafely_noPreviousEntry_returnsFalse() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        val nav = mockk<NavController>(relaxed = true)
        every { nav.currentBackStack } returns MutableStateFlow(emptyList())
        every { nav.previousBackStackEntry } returns null

        assertFalse(nav.popBackStackSafely())
        verify(exactly = 0) { nav.popBackStack() }
    }

    @Test
    fun popBackStackSafely_withPreviousEntry_popsAndReturnsTrue() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        val nav = mockk<NavController>(relaxed = true)
        val prevEntry = mockk<NavBackStackEntry>()
        every { prevEntry.destination } returns dest("home")
        every { nav.currentBackStack } returns MutableStateFlow(emptyList())
        every { nav.previousBackStackEntry } returns prevEntry
        every { nav.popBackStack() } returns true

        // First pop: last time is 0 -> well beyond debounce -> proceeds.
        assertTrue(nav.popBackStackSafely())
        verify(exactly = 1) { nav.popBackStack() }
    }

    @Test
    fun popBackStackSafely_rapidSecondCall_isDebounced() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        val nav = mockk<NavController>(relaxed = true)
        val prevEntry = mockk<NavBackStackEntry>()
        every { prevEntry.destination } returns dest("home")
        every { nav.currentBackStack } returns MutableStateFlow(emptyList())
        every { nav.previousBackStackEntry } returns prevEntry
        every { nav.popBackStack() } returns true

        assertTrue(nav.popBackStackSafely())  // records timestamp
        // Immediate second call should be blocked by the 600ms debounce.
        assertFalse(nav.popBackStackSafely())
        verify(exactly = 1) { nav.popBackStack() }
    }
}
