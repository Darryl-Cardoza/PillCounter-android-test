package com.rite.pillcounting.core.utils.common

import androidx.navigation.NavController
import java.util.WeakHashMap

fun NavController.navigateSafely(route: String) {
    //If you are already on the same destination, do nothing
    if (currentDestination?.route == route) return

    //If the destination is already on top, don't create another instance
    navigate(route) {
        launchSingleTop = true
    }
}

// Tracks last pop time per NavController instance so the debounce survives screen transitions.
// WeakHashMap avoids leaking NavController references.
private val lastPopTimeMs = WeakHashMap<NavController, Long>()
private const val POP_DEBOUNCE_MS = 600L

/**
 * Pops the back stack only when:
 * 1. There is a destination to pop to (prevents blank screen at root).
 * 2. The last pop was more than [POP_DEBOUNCE_MS] ago (prevents rapid-tap chaining
 *    across screen transitions, where each new screen resets per-composable state).
 */
fun NavController.popBackStackSafely(): Boolean {
    val stack = currentBackStack.value.map { it.destination.route }
    val prev = previousBackStackEntry?.destination?.route
    android.util.Log.d("NavDebug", "popBackStackSafely: stack=$stack prev=$prev")
    if (previousBackStackEntry == null) {
        android.util.Log.d("NavDebug", "BLOCKED — no previous entry")
        return false
    }
    val now = System.currentTimeMillis()
    val last = lastPopTimeMs[this] ?: 0L
    android.util.Log.d("NavDebug", "time since last pop=${now - last}ms")
    if (now - last < POP_DEBOUNCE_MS) {
        android.util.Log.d("NavDebug", "BLOCKED — debounce")
        return false
    }
    lastPopTimeMs[this] = now
    android.util.Log.d("NavDebug", "POPPING")
    return popBackStack()
}