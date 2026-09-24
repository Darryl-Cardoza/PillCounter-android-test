package com.dispensesure.retail.core.faceAuth.data

import com.dispensesure.retail.core.faceAuth.logic.SessionLockController
import com.dispensesure.retail.core.room.dao.FaceProfileDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.models.FaceProfileEntity
import com.dispensesure.retail.core.room.models.UserEntity
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperatorNameProviderTest {

    private val faceProfileDao: FaceProfileDao = mockk(relaxed = true)
    private val userDao: UserDao = mockk(relaxed = true)
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)
    private val sessionLockController: SessionLockController = mockk(relaxed = true)

    private val verifiedId = MutableStateFlow<Long?>(null)

    private val provider = OperatorNameProvider(
        faceProfileDao, userDao, preferenceHelper, sessionLockController
    )

    private fun face(id: Long, first: String, last: String, enabled: Boolean = true) =
        FaceProfileEntity(
            id = id, firstName = first, lastName = last, email = null,
            isEnabled = enabled, createdAt = 0L
        )

    private val account = UserEntity(localId = 7L, userId = "u-77", fName = "Jane", lName = "Doe")

    /** Wires the session flow plus a resolvable logged-in account. */
    private fun givenAccount() {
        every { sessionLockController.verifiedFaceProfileId } returns verifiedId
        every { preferenceHelper.getLocalId() } returns 7L
        coEvery { userDao.getByLocalId(7L) } returns account
    }

    @Test
    fun `uses the profile that verified this session`() = runTest {
        givenAccount()
        verifiedId.value = 1L
        coEvery { faceProfileDao.getById(1L) } returns face(1L, "Bruce", "Wayne")

        val operator = provider()

        assertEquals("Bruce", operator.firstName)
        assertEquals("Wayne", operator.lastName)
    }

    @Test
    fun `falls back to the account when nobody has verified yet`() = runTest {
        givenAccount()
        verifiedId.value = null

        val operator = provider()

        assertEquals("Jane", operator.firstName)
        assertEquals("Doe", operator.lastName)
    }

    @Test
    fun `falls back to the account when the verified profile was switched off`() = runTest {
        // A verified, then A was toggled off. B may still be enabled with older verify
        // history — it must not be promoted, because B never verified into this session.
        givenAccount()
        verifiedId.value = 1L
        coEvery { faceProfileDao.getById(1L) } returns face(1L, "Aaron", "Ant", enabled = false)

        val operator = provider()

        assertEquals("Jane", operator.firstName)
        assertEquals("Doe", operator.lastName)
    }

    @Test
    fun `falls back to the account when the verified profile was deleted`() = runTest {
        givenAccount()
        verifiedId.value = 1L
        coEvery { faceProfileDao.getById(1L) } returns null

        val operator = provider()

        assertEquals("Jane", operator.firstName)
        assertEquals("Doe", operator.lastName)
    }

    @Test
    fun `returns nulls when there is no verified face and no resolvable account`() = runTest {
        every { sessionLockController.verifiedFaceProfileId } returns verifiedId
        verifiedId.value = null
        every { preferenceHelper.getLocalId() } returns 0L

        val operator = provider()

        assertNull(operator.firstName)
        assertNull(operator.lastName)
    }

    @Test
    fun `observe emits the same resolution as invoke`() = runTest {
        givenAccount()
        verifiedId.value = 1L
        coEvery { faceProfileDao.getById(1L) } returns face(1L, "Bruce", "Wayne")
        every { faceProfileDao.observeAll() } returns flowOf(emptyList())
        every { userDao.observeByLocalId(7L) } returns flowOf(account)

        val operator = provider.observe().first()

        assertEquals("Bruce", operator.firstName)
        assertEquals("Wayne", operator.lastName)
    }

    @Test
    fun `observe re-emits the account name once the verified profile is switched off`() = runTest {
        givenAccount()
        verifiedId.value = 1L
        every { faceProfileDao.observeAll() } returns flowOf(emptyList())
        every { userDao.observeByLocalId(7L) } returns flowOf(account)
        coEvery { faceProfileDao.getById(1L) } returns face(1L, "Bruce", "Wayne")

        assertEquals("Bruce", provider.observe().first().firstName)

        coEvery { faceProfileDao.getById(1L) } returns face(1L, "Bruce", "Wayne", enabled = false)

        assertEquals("Jane", provider.observe().first().firstName)
    }

    @Test
    fun `display joins both parts and is null when both are missing`() {
        assertEquals("Bruce Wayne", OperatorName("Bruce", "Wayne").display())
        assertEquals("Bruce", OperatorName("Bruce", null).display())
        assertNull(OperatorName(null, null).display())
    }

    @Test
    fun `display skips blank parts instead of leaving a stray space`() {
        // A row can hold "" rather than null — listOfNotNull would keep it and emit " Wayne".
        assertEquals("Wayne", OperatorName("", "Wayne").display())
        assertEquals("Bruce", OperatorName("Bruce", "   ").display())
        assertNull(OperatorName("", "").display())
    }
}
