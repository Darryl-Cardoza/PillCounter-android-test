package com.dispensesure.retail.core.audit.key

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.room.dao.DekVersionDao
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.security.DatabaseKeyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Copies the wrap the DB DEK is stored under now into `dek_versions`, one row per new wrapped value. */
@Singleton
class DekVersionRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dekVersionDao: DekVersionDao
) {
    // The dashboard and the audit drain both record; one at a time, or both could add the same wrap.
    private val recordMutex = Mutex()

    suspend fun recordCurrentWrap(deviceKey: String) = recordMutex.withLock {
        // Creates the DEK if missing: debug builds don't open Room with it, so nothing else may have yet.
        DatabaseKeyProvider.createDekIfMissing(context)
        val currentWrap = DatabaseKeyProvider.currentWrap(context) ?: return@withLock
        val activeRow = dekVersionDao.getActive()
        if (activeRow != null && activeRow.wrappedDek.contentEquals(currentWrap.wrappedDek)) return@withLock
        // The table lives only as long as the DEK (a new DEK wipes the DB), so any existing row means the same DEK.
        dekVersionDao.insertAsActive(
            DekVersionEntity(
                dekKeyId = AuditLogConfig.DEK_KEY_ID_PREFIX + deviceKey,
                version = activeRow?.version ?: FIRST_VERSION,
                wrappedDek = currentWrap.wrappedDek,
                kekKeyId = currentWrap.kekKeyId,
                kekVersion = currentWrap.kekVersion,
                kekAlgorithm = currentWrap.kekAlgorithm,
                isActive = true,
                isRegistered = activeRow?.isRegistered ?: false,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private companion object {
        const val FIRST_VERSION = 1
    }
}
