package com.rite.pillcounting.core.utils.device

import com.google.firebase.installations.FirebaseInstallations
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Supplies a stable per-install device_key backed by the Firebase Installations ID.
 * The ID is cached after first fetch so subsequent calls (e.g. every login) don't
 * hit Firebase again; a fresh install/reinstall gets a new ID and consumes a new
 * device slot, which is the desired behavior.
 */
@Singleton
class DeviceKeyProvider @Inject constructor(
    private val preferenceHelper: PreferenceHelper
) {

    private val logger = AppLogger.create<DeviceKeyProvider>()

    suspend fun getDeviceKey(): String {
        preferenceHelper.getDeviceKey()?.let { return it }

        val deviceKey = fetchInstallationId()
        preferenceHelper.saveDeviceKey(deviceKey)
        logger.i("Fetched and cached new device key from Firebase Installations")
        return deviceKey
    }

    private suspend fun fetchInstallationId(): String = suspendCancellableCoroutine { continuation ->
        FirebaseInstallations.getInstance().id
            .addOnSuccessListener { id -> continuation.resume(id) }
            .addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    }
}
