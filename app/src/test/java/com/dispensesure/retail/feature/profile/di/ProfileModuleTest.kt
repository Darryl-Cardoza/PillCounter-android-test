package com.dispensesure.retail.feature.profile.di

import com.dispensesure.retail.core.auth.AuthEventBus
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.dispensesure.retail.feature.profile.data.ProfileRepository
import com.dispensesure.retail.feature.profile.data.remote.IProfileApi
import com.dispensesure.retail.feature.profile.domain.data.IProfileRepository
import com.dispensesure.retail.feature.settings.data.remote.IApplicationSettingInterface
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
        val authEventBus = mockk<AuthEventBus>(relaxed = true)

        val repository: IProfileRepository = ProfileModule.provideProfileRepository(
            api = api,
            userDao = userDao,
            preferenceHelper = preferenceHelper,
            applicationSettingApi = applicationSettingApi,
            ioDispatcher = ioDispatcher,
            authEventBus = authEventBus
        )

        assertNotNull(repository)
        assertTrue(repository is ProfileRepository)
    }
}
