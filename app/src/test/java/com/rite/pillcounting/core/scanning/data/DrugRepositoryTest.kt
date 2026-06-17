package com.rite.pillcounting.core.scanning.data

import com.rite.pillcounting.core.scanning.data.remote.IDrugAPI
import com.rite.pillcounting.core.scanning.domain.model.DrugComparisonData
import com.rite.pillcounting.core.scanning.domain.model.DrugDataResponse
import com.rite.pillcounting.core.scanning.domain.model.DrugRegulatory
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.scanning.domain.model.NdcDrugInfo
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [DrugRepository].
 *
 * Covers authentication guard, successful field mapping, NDC-mismatch null path, and all
 * three exception types (HttpException, IOException, RuntimeException).
 * No MainDispatcherRule needed — repository has no viewModelScope.
 */
class DrugRepositoryTest {

    private val api: IDrugAPI = mockk()
    private val preferenceHelper: PreferenceHelper = mockk(relaxed = true)

    private lateinit var repository: DrugRepository

    private val request = GetNdcRequestModel(target_ndc = "", scanned_ndc = "12345678901234")

    @Before
    fun setup() {
        repository = DrugRepository(api, preferenceHelper)
    }

    @After
    fun tearDown() { unmockkAll() }

    private fun successResponse(ndcInfo: NdcDrugInfo? = null): DrugDataResponse = DrugDataResponse(
        data = DrugComparisonData(is_ndc_same = true, is_ndc_equivalent = null, scanned_ndc = ndcInfo)
    )

    // DRUG_REPO_001
    @Test
    fun `getDrugInfoByNdc returns null when access token is null`() = runTest {
        every { preferenceHelper.getAccessToken() } returns null

        assertNull(repository.getDrugInfoByNdc(request))
    }

    // DRUG_REPO_002
    @Test
    fun `getDrugInfoByNdc returns null when access token is blank`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "   "

        assertNull(repository.getDrugInfoByNdc(request))
    }

    // DRUG_REPO_003
    @Test
    fun `getDrugInfoByNdc maps all DrugInfo fields correctly on success`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        val ndcInfo = NdcDrugInfo(
            drug_code = "12345678901234",
            manufacturer = "PharmaCo",
            lookup_name = "Aspirin 325mg",
            is_hazardous = true,
            regulatory = DrugRegulatory(schedule = "II")
        )
        coEvery { api.getDrugInfoByNdc(any(), any()) } returns successResponse(ndcInfo)

        val result = repository.getDrugInfoByNdc(request)

        assertNotNull(result)
        assertEquals("12345678901234", result!!.ndc)
        assertEquals("PharmaCo", result.brandName)
        assertEquals("Aspirin 325mg", result.genericName)
        assertEquals(true, result.isHazardous)
        assertEquals("II", result.drugType)
    }

    // DRUG_REPO_004
    @Test
    fun `getDrugInfoByNdc returns null when response data is null`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        coEvery { api.getDrugInfoByNdc(any(), any()) } returns DrugDataResponse(data = null)

        assertNull(repository.getDrugInfoByNdc(request))
    }

    // DRUG_REPO_005
    @Test
    fun `getDrugInfoByNdc returns DrugInfo when is_ndc_equivalent is true even if not same`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        val requestWithTarget = GetNdcRequestModel(target_ndc = "00000000000000", scanned_ndc = "12345678901234")
        val ndcInfo = NdcDrugInfo(drug_code = "12345678901234", lookup_name = "Metoprolol")
        coEvery { api.getDrugInfoByNdc(any(), any()) } returns DrugDataResponse(
            data = DrugComparisonData(is_ndc_same = false, is_ndc_equivalent = true, scanned_ndc = ndcInfo)
        )

        val result = repository.getDrugInfoByNdc(requestWithTarget)

        assertNotNull(result)
        assertEquals("12345678901234", result!!.ndc)
    }

    // DRUG_REPO_006
    @Test
    fun `getDrugInfoByNdc returns null when NDC is neither same nor equivalent and target_ndc is non-empty`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        val requestWithTarget = GetNdcRequestModel(
            target_ndc = "00000000000000",
            scanned_ndc = "12345678901234"
        )
        val ndcInfo = NdcDrugInfo(drug_code = "12345678901234")
        coEvery { api.getDrugInfoByNdc(any(), any()) } returns DrugDataResponse(
            data = DrugComparisonData(is_ndc_same = false, is_ndc_equivalent = false, scanned_ndc = ndcInfo)
        )

        assertNull(repository.getDrugInfoByNdc(requestWithTarget))
    }

    // DRUG_REPO_007
    @Test
    fun `getDrugInfoByNdc returns DrugInfo when target_ndc is empty even if NDC flags are false`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        val ndcInfo = NdcDrugInfo(drug_code = "12345678901234", lookup_name = "Ibuprofen")
        coEvery { api.getDrugInfoByNdc(any(), any()) } returns DrugDataResponse(
            data = DrugComparisonData(is_ndc_same = false, is_ndc_equivalent = false, scanned_ndc = ndcInfo)
        )

        val result = repository.getDrugInfoByNdc(
            GetNdcRequestModel(target_ndc = "", scanned_ndc = "12345678901234")
        )

        assertNotNull(result)
        assertEquals("12345678901234", result!!.ndc)
    }

    // DRUG_REPO_008
    @Test
    fun `getDrugInfoByNdc returns null on HttpException`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        coEvery { api.getDrugInfoByNdc(any(), any()) } throws HttpException(
            Response.error<Any>(401, "".toResponseBody(null))
        )

        assertNull(repository.getDrugInfoByNdc(request))
    }

    // DRUG_REPO_009
    @Test
    fun `getDrugInfoByNdc returns null on IOException`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        coEvery { api.getDrugInfoByNdc(any(), any()) } throws IOException("connection refused")

        assertNull(repository.getDrugInfoByNdc(request))
    }

    // DRUG_REPO_010
    @Test
    fun `getDrugInfoByNdc returns null on unexpected RuntimeException`() = runTest {
        every { preferenceHelper.getAccessToken() } returns "valid-token"
        coEvery { api.getDrugInfoByNdc(any(), any()) } throws RuntimeException("unexpected failure")

        assertNull(repository.getDrugInfoByNdc(request))
    }
}
