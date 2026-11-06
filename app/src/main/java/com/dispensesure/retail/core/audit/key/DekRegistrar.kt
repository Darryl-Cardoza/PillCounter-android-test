package com.dispensesure.retail.core.audit.key

import com.dispensesure.retail.core.audit.crypto.Sha256
import com.dispensesure.retail.core.room.dao.DekVersionDao
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import java.io.IOException
import javax.inject.Inject

/**
 * Sends the active DEK version to POST /mobile/devices/keys until the server holds it.
 * Only a new DEK is ever unregistered: a KEK rotation keeps the version, so it sends nothing.
 */
class DekRegistrar @Inject constructor(
    private val dekVersionDao: DekVersionDao,
    private val api: IDeviceKeysApi,
    private val preferenceHelper: PreferenceHelper
) {
    private val logger = AppLogger.create<DekRegistrar>()

    /** The active row once the server holds its version, else null; the next drain tries again. */
    suspend fun registerIfNeeded(deviceKey: String): DekVersionEntity? {
        val activeRow = dekVersionDao.getActive() ?: return null
        if (activeRow.isRegistered) return activeRow
        // Always the same row's bytes: a re-wrap differs, and would hit version_conflict if an earlier send landed.
        val rowToSend = dekVersionDao.getOldestServerWrapped(activeRow.version, DatabaseKeyProvider.BOOTSTRAP_KEK_ID)
            ?: return null
        var version = rowToSend.version
        repeat(MAX_RENUMBERS + 1) {
            val accessToken = preferenceHelper.getAccessToken() ?: return null
            val reply = try {
                DeviceKeyReply.from(
                    api.registerKey("Bearer $accessToken", DeviceKeyRequest.of(rowToSend, version, deviceKey).toJsonBody())
                )
            } catch (e: IOException) {
                logger.w("DEK registration network error", e, event = LogEvent.DEK_REGISTER_FAILED)
                return null
            }
            when {
                reply.isStored() -> return markRegisteredIfFingerprintMatches(reply, rowToSend, version)
                reply.isVersionConflict() -> {
                    val nextFreeVersion = checkNotNull(reply.nextFreeVersion)
                    dekVersionDao.renumberUnregistered(version, nextFreeVersion)
                    version = nextFreeVersion
                }
                else -> {
                    logRefusal(reply)
                    return null
                }
            }
        }
        return null
    }

    // Guide §1.5: a different fingerprint means the server holds other bytes, so the key isn't registered.
    private suspend fun markRegisteredIfFingerprintMatches(
        reply: DeviceKeyReply,
        sentRow: DekVersionEntity,
        version: Int
    ): DekVersionEntity? {
        if (reply.fingerprint != Sha256.hexOf(sentRow.wrappedDek)) {
            logger.e("DEK registration fingerprint mismatch for version $version", event = LogEvent.DEK_REGISTER_REJECTED)
            return null
        }
        dekVersionDao.markVersionRegistered(version, DatabaseKeyProvider.BOOTSTRAP_KEK_ID)
        return dekVersionDao.getActive()
    }

    private fun logRefusal(reply: DeviceKeyReply) {
        val message = "DEK registration refused: ${reply.httpStatus} ${reply.code}"
        // A 422 means our request is wrong; resending it unchanged can't help.
        if (reply.httpStatus == 422) logger.e(message, event = LogEvent.DEK_REGISTER_REJECTED)
        else logger.w(message, event = LogEvent.DEK_REGISTER_FAILED)
    }

    private companion object {
        const val MAX_RENUMBERS = 1
    }
}
