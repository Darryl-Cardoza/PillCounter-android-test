package com.rite.pillcounting.core.utils.device

import android.content.Context
import android.provider.Settings
import com.rite.pillcounting.core.utils.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies a stable per-device device_key backed by SSAID (Settings.Secure.ANDROID_ID).
 *
 * SSAID is scoped per (device, user, app-signing-key) on Android 8+ and survives
 * uninstall/reinstall when the reinstall uses the same signing key. Factory reset
 * resets it. Emulators and some vendor ROMs may return null or an empty string —
 * in that case [getDeviceKey] returns null so callers can refuse to send a blank
 * identifier instead of colliding with every other device in the same state.
 */
@Singleton
class DeviceKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val logger = AppLogger.create<DeviceKeyProvider>()

    /** Returns SSAID for this app/device, or null when the platform reports empty/null. */
    suspend fun getDeviceKey(): String? {
        val id = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        logger.d("Resolved device_key (SSAID) length=${id?.length ?: 0}")
        return id?.takeIf { it.isNotBlank() }
    }
}
