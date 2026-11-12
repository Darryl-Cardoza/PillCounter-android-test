package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.AnalyticsObserver
import com.dispensesure.retail.core.analytics.di.AnalyticsDispatcher
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.analytics.upload.AnalyticsUploader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point for the analytics subsystem.
 *
 * All three methods return immediately — analytics never blocks the caller. Set [isEnabled] to
 * `false` (e.g. in tests) to make all entry points no-ops without unwiring anything.
 */
@Singleton
class AnalyticsService @Inject constructor(
    private val observer: AnalyticsObserver,
    private val store: AnalyticsStore,
    private val uploader: AnalyticsUploader,
    @AnalyticsDispatcher dispatcher: CoroutineDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Called once from [com.dispensesure.retail.PillCountingApplication.onCreate]. */
    fun start() {
        if (!isEnabled) return
        observer.startObserving(scope)
        scope.launch { store.pruneIfNeeded() }
    }

    /** Called from [com.dispensesure.retail.MainActivity.onResume]. */
    fun applicationDidBecomeActive() {
        if (!isEnabled) return
        scope.launch {
            store.pruneIfNeeded()
            uploader.drainIfNeeded()
        }
    }

    /** Called from the OTP/login success path after the user is authenticated. */
    fun userDidLogin() {
        if (!isEnabled) return
        scope.launch {
            store.pruneIfNeeded()
            uploader.drainIfNeeded()
        }
    }

    companion object {
        var isEnabled: Boolean = true
    }
}
