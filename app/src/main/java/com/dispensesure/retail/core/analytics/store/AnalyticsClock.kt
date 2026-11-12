package com.dispensesure.retail.core.analytics.store

/** Clock abstraction so day-rollover logic is testable without waiting for midnight. */
fun interface AnalyticsClock {
    fun nowMillis(): Long
}

/** Production binding — reads the system wall clock. */
object SystemAnalyticsClock : AnalyticsClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
