package com.rite.pillcounting.feature.settings.di

import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import com.rite.pillcounting.feature.settings.data.ApplicationSettingsRepository
import com.rite.pillcounting.feature.settings.data.remote.IApplicationSettingInterface
import com.rite.pillcounting.feature.settings.domain.data.IApplicationSettingsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [ApplicationSettingsModule]'s `@Provides` functions.
 */
class ApplicationSettingsModuleTest {

    @Test
    fun `provideApplicationSettingApi returns non-null IApplicationSettingInterface`() {
        val retrofit = mockk<Retrofit>()
        every {
            retrofit.create(IApplicationSettingInterface::class.java)
        } returns mockk<IApplicationSettingInterface>(relaxed = true)

        val api = ApplicationSettingsModule.provideApplicationSettingApi(retrofit)

        assertNotNull(api)
    }

    @Test
    fun `provideApplicationSettingsRepository returns non-null ApplicationSettingsRepository`() {
        val apiService = mockk<IApplicationSettingInterface>(relaxed = true)
        val preferenceHelper = mockk<PreferenceHelper>(relaxed = true)

        val repository: IApplicationSettingsRepository =
            ApplicationSettingsModule.provideApplicationSettingsRepository(
                apiService = apiService,
                preferenceHelper = preferenceHelper
            )

        assertNotNull(repository)
        assertTrue(repository is ApplicationSettingsRepository)
    }
}
