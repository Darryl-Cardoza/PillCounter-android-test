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
 * resets it. Emulators and some vendor ROMs may return an empty string.
 *
 * No caching layer: the platform lookup is a single ContentResolver call.
 */
@Singleton
class DeviceKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val logger = AppLogger.create<DeviceKeyProvider>()

    /**
     * Returns the SSAID for this app on this device/user.
     *
     * Description:
     * Reads Settings.Secure.ANDROID_ID via the app's ContentResolver.
     *
     * What it does:
     * - Performs a single sync platform lookup.
     * - Falls back to an empty string if the platform returns null.
     * - Logs the resolved length only (no raw value at info level).
     *
     * Note:
     * The `suspend` modifier is retained so existing call sites and mocks continue
     * to compile unchanged; the body does not actually suspend.
     *
     * @return SSAID string, or "" when unavailable.
     *
     * Example Usage:
     * val deviceKey = deviceKeyProvider.getDeviceKey()
     */
    suspend fun getDeviceKey(): String {
        val id = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""
        logger.i("Resolved device_key (SSAID) length=${id.length}")
        return id
    }
}
