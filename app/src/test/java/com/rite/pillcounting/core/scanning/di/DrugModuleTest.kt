package com.rite.pillcounting.core.scanning.di

import com.rite.pillcounting.core.scanning.data.DrugRepository
import com.rite.pillcounting.core.scanning.data.remote.IDrugAPI
import com.rite.pillcounting.core.scanning.domain.data.IDrugRepository
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * Unit tests for [DrugModule]'s `@Provides` functions.
 *
 * Both providers are invoked directly with mocked arguments and asserted non-null.
 *
 * SKIPPED: none.
 */
class DrugModuleTest {

    @Test
    fun `provideDrugApi returns non-null IDrugAPI`() {
        val retrofit = mockk<Retrofit>()
        every { retrofit.create(IDrugAPI::class.java) } returns mockk<IDrugAPI>(relaxed = true)

        val api: IDrugAPI = DrugModule.provideDrugApi(retrofit)

        assertNotNull(api)
    }

    @Test
    fun `provideDrugRepository returns non-null DrugRepository`() {
        val drugApi = mockk<IDrugAPI>(relaxed = true)
        val preferenceHelper = mockk<PreferenceHelper>(relaxed = true)

        val repository: IDrugRepository =
            DrugModule.provideDrugRepository(drugApi, preferenceHelper)

        assertNotNull(repository)
        assertTrue(repository is DrugRepository)
    }
}
