package com.dispensesure.retail.core.analytics

import com.dispensesure.retail.core.analytics.collect.AnalyticsObserver
import com.dispensesure.retail.core.analytics.store.AnalyticsStore
import com.dispensesure.retail.core.analytics.upload.AnalyticsUploader
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class AnalyticsServiceTest {

    private lateinit var observer: AnalyticsObserver
    private lateinit var store: AnalyticsStore
    private lateinit var uploader: AnalyticsUploader

    @Before
    fun setUp() {
        AnalyticsService.isEnabled = true
        observer = mockk(relaxed = true)
        store = mockk(relaxed = true)
        uploader = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        AnalyticsService.isEnabled = true
    }

    private fun makeService(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        AnalyticsService(observer, store, uploader, dispatcher)

    @Test
    fun `isEnabled=false makes start a no-op`() = runTest {
        AnalyticsService.isEnabled = false
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.start()
        advanceUntilIdle()

        coVerify(exactly = 0) { observer.startObserving(any()) }
        coVerify(exactly = 0) { store.pruneIfNeeded() }
    }

    @Test
    fun `isEnabled=false makes applicationDidBecomeActive a no-op`() = runTest {
        AnalyticsService.isEnabled = false
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.applicationDidBecomeActive()
        advanceUntilIdle()

        coVerify(exactly = 0) { store.pruneIfNeeded() }
        coVerify(exactly = 0) { uploader.drainIfNeeded() }
    }

    @Test
    fun `isEnabled=false makes userDidLogin a no-op`() = runTest {
        AnalyticsService.isEnabled = false
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.userDidLogin()
        advanceUntilIdle()

        coVerify(exactly = 0) { store.pruneIfNeeded() }
        coVerify(exactly = 0) { uploader.drainIfNeeded() }
    }

    @Test
    fun `start calls observer startObserving and store pruneIfNeeded`() = runTest {
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.start()
        advanceUntilIdle()

        coVerify { observer.startObserving(any()) }
        coVerify { store.pruneIfNeeded() }
    }

    @Test
    fun `applicationDidBecomeActive calls pruneIfNeeded and drainIfNeeded`() = runTest {
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.applicationDidBecomeActive()
        advanceUntilIdle()

        coVerify { store.pruneIfNeeded() }
        coVerify { uploader.drainIfNeeded() }
    }

    @Test
    fun `userDidLogin calls pruneIfNeeded and drainIfNeeded`() = runTest {
        val service = makeService(StandardTestDispatcher(testScheduler))

        service.userDidLogin()
        advanceUntilIdle()

        coVerify { store.pruneIfNeeded() }
        coVerify { uploader.drainIfNeeded() }
    }
}
