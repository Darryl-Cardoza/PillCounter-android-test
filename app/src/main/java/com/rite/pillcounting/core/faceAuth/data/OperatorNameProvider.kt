package com.rite.pillcounting.core.faceAuth.data

import com.rite.pillcounting.core.faceAuth.logic.SessionLockController
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * The operator's name as reported on HL7. Either part may be null when neither a
 * face profile nor a logged-in account can supply one.
 *
 * @param firstName Given name.
 * @param lastName Family name.
 */
data class OperatorName(val firstName: String?, val lastName: String?) {

    /**
     * Joins both parts for fields carrying a single display string, e.g. `batch.userName`.
     *
     * @return "First Last", or null when neither part is set.
     */
    fun display(): String? =
        listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { null }
}

/**
 * Resolves who the app reports as the operator.
 *
 * Description:
 * The face user who verified into **this session** wins — that is the person actually
 * at the device. Everything else falls back to the logged-in account, which may be a
 * shared pharmacy login: nobody has verified yet, no profiles are enrolled, or the
 * profile that did verify has since been switched off.
 *
 * Deliberately not driven by [FaceProfileEntity.lastUsedAt]. That column is persistent
 * history, so it would keep naming an operator across restarts, and switching the real
 * operator off would silently promote some other enrolled profile that never verified.
 * Naming that person again takes a lock (or a relaunch) and a fresh verify.
 *
 * @param faceProfileDao Source of the verified profile's current row.
 * @param userDao Source of the logged-in account's name.
 * @param preferenceHelper Source of the current session's `localId`.
 * @param sessionLockController Source of who verified into this session.
 *
 * Example Usage:
 * val operator = operatorNameProvider()
 */
class OperatorNameProvider @Inject constructor(
    private val faceProfileDao: FaceProfileDao,
    private val userDao: UserDao,
    private val preferenceHelper: PreferenceHelper,
    private val sessionLockController: SessionLockController
) {
    /**
     * @return The verified face user's name, else the logged-in account's, else nulls.
     */
    suspend operator fun invoke(): OperatorName {
        verifiedFaceUser()?.let { return OperatorName(it.firstName, it.lastName) }
        val localId = preferenceHelper.getLocalId()
        if (localId <= 0L) return OperatorName(null, null)
        val user = userDao.getByLocalId(localId)
        return OperatorName(user?.fName, user?.lName)
    }

    /** The profile that verified this session, or null if none did or it was switched off since. */
    private suspend fun verifiedFaceUser(): FaceProfileEntity? =
        sessionLockController.verifiedFaceProfileId.value
            ?.let { faceProfileDao.getById(it) }
            ?.takeIf { it.isEnabled }

    /**
     * Same rule as [invoke], re-evaluated whenever the verified identity, the enrolled
     * profiles, or the account row change — for live UI such as the dashboard's
     * terminal/user line.
     *
     * Description:
     * The upstream flows are only change triggers; resolution always goes back through
     * [invoke] so the rule stays in one place. `localId` is read once when collection
     * starts, matching `observeUserDetail`.
     *
     * @return A [Flow] emitting the current operator name on every relevant change.
     *
     * Example Usage:
     * operatorNameProvider.observe().collect { name -> ... }
     */
    fun observe(): Flow<OperatorName> {
        val localId = preferenceHelper.getLocalId()
        val userChanges = if (localId > 0L) userDao.observeByLocalId(localId) else flowOf(null)
        return combine(
            sessionLockController.verifiedFaceProfileId,
            faceProfileDao.observeAll(),
            userChanges
        ) { _, _, _ -> invoke() }
    }
}
