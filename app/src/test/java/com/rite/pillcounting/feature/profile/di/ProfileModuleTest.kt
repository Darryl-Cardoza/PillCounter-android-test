package com.rite.pillcounting.feature.profile.di

import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.profile.data.ProfileRepository
import com.rite.pillcounting.feature.profile.data.remote.IProfileApi
import com.rite.pillcounting.feature.profile.domain.data.IProfileRepository
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [ProfileModule]'s `@Provides` functions.
 *
 * Each provider is invoked directly with mocked arguments and the returned
 * instance is asserted non-null (and of the expected type for repositories).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileModuleTest {

    @Test
    fun `provideProfileApi returns non-null IProfileApi`() {
        val retrofit = mockk<Retrofit>()
        every { retrofit.create(IProfileApi::class.java) } returns mockk<IProfileApi>(relaxed = true)

        val api = ProfileModule.provideProfileApi(retrofit)

        assertNotNull(api)
    }

    @Test
    fun `provideProfileRepository returns non-null ProfileRepository`() {
        val api = mockk<IProfileApi>(relaxed = true)
        val userDao = mockk<UserDao>(relaxed = true)
        val preferenceHelper = mockk<PreferenceHelper>(relaxed = true)
        val applicationSettingApi = mockk<IApplicationSettingInterface>(relaxed = true)
        val ioDispatcher = UnconfinedTestDispatcher()

        val repository: IProfileRepository = ProfileModule.provideProfileRepository(
            api = api,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
            applicationSettingApi = applicationSettingApi,
            ioDispatcher = ioDispatcher
        )

        assertNotNull(repository)
        assertTrue(repository is ProfileRepository)
    }
}
