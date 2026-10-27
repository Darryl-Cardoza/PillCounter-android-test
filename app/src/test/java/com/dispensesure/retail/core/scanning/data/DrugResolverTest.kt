package com.dispensesure.retail.core.scanning.data

import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.domain.data.IDrugRepository
import com.dispensesure.retail.core.scanning.domain.model.DrugInfo
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrugResolverTest {

    private val drugMasterDao: DrugMasterDao = mockk()
    private val drugRepository: IDrugRepository = mockk()
    private val drugImageDownloader: DrugImageDownloader = mockk()
    private val resolver = DrugResolver(drugMasterDao, drugRepository, drugImageDownloader)

    @Before
    fun setup() {
        coEvery { drugMasterDao.getDrugByGtin(any()) } returns null
        coEvery { drugMasterDao.getDrugByNdc(any()) } returns null
        coEvery { drugMasterDao.upsertPreservingId(any()) } returns SAVED_ID
        coEvery { drugImageDownloader.downloadAndSave(any(), any()) } returns IMAGE_PATH
    }

    @Test
    fun `saved drug found by GTIN is used without asking the server`() = runTest {
        val saved = savedDrug(ndc = NDC)
        coEvery { drugMasterDao.getDrugByGtin(GTIN) } returns saved

        val result = resolver.resolve(GTIN)

        assertEquals(DrugLookup.Found(saved, fromServer = false), result)
        coVerify(exactly = 0) { drugRepository.getDrugInfoByNdc(any()) }
    }

    @Test
    fun `saved drug found by NDC is used`() = runTest {
        val saved = savedDrug(ndc = NDC)
        coEvery { drugMasterDao.getDrugByNdc(NDC) } returns saved

        assertEquals(DrugLookup.Found(saved, fromServer = false), resolver.resolve(NDC))
    }

    @Test
    fun `saved drug with a blank name is fetched again`() = runTest {
        coEvery { drugMasterDao.getDrugByNdc(NDC) } returns savedDrug(ndc = NDC, name = "")
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo()

        val result = resolver.resolve(NDC)

        assertTrue(result is DrugLookup.Found && result.fromServer)
    }

    @Test
    fun `saved drug that is not the expected one goes to the server with the expected NDC`() = runTest {
        coEvery { drugMasterDao.getDrugByGtin(GTIN) } returns savedDrug(ndc = "OTHER")
        val request = slot<GetNdcRequestModel>()
        coEvery { drugRepository.getDrugInfoByNdc(capture(request)) } returns serverInfo()

        resolver.resolve(GTIN, expectedNdc = NDC)

        assertEquals(NDC, request.captured.target_drug)
        assertEquals(GTIN, request.captured.scanned_drug)
    }

    @Test
    fun `server drug is saved in full and returned with the saved id`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo()
        val saved = slot<DrugMasterEntity>()
        coEvery { drugMasterDao.upsertPreservingId(capture(saved)) } returns SAVED_ID

        val result = resolver.resolve(GTIN, scannedGtin = GTIN) as DrugLookup.Found

        val expected = DrugMasterEntity(
            ndc = NDC, drugName = "Aspirin", drugType = "CII", gtin = GTIN, packageQty = 100,
            isHazardous = true, strength = "81 mg", dosageForm = "TABLET", drugImagePath = IMAGE_PATH,
            createdAt = saved.captured.createdAt,
        )
        assertEquals(expected, saved.captured)
        assertEquals(expected.copy(drugId = SAVED_ID), result.drug)
        assertTrue(result.fromServer)
        assertFalse(result.isEquivalent)
        assertEquals(IMAGE_URL, result.imageUrl)
    }

    @Test
    fun `equivalent drug is flagged`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo(isEquivalent = true)

        val result = resolver.resolve(GTIN, expectedNdc = "EXPECTED") as DrugLookup.Found

        assertTrue(result.isEquivalent)
    }

    @Test
    fun `server without a name uses nameIfMissing and names the image file after the NDC`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo(genericName = null)

        val result = resolver.resolve(GTIN, nameIfMissing = "Unknown Drug") as DrugLookup.Found

        assertEquals("Unknown Drug", result.drug.drugName)
        coVerify { drugImageDownloader.downloadAndSave(IMAGE_URL, NDC) }
    }

    @Test
    fun `server without a name and no nameIfMissing is NoName and saves nothing`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo(genericName = " ")

        assertEquals(DrugLookup.NoName, resolver.resolve(GTIN))
        coVerify(exactly = 0) { drugMasterDao.upsertPreservingId(any()) }
    }

    @Test
    fun `server drug with a blank NDC is saved under the scanned code`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns serverInfo(ndc = "")

        val result = resolver.resolve(NDC) as DrugLookup.Found

        assertEquals(NDC, result.drug.ndc)
    }

    @Test
    fun `unknown drug with nameIfUnknown saves a minimal record`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        val result = resolver.resolve(NDC, nameIfUnknown = "Aspirin") as DrugLookup.Found

        assertEquals(NDC, result.drug.ndc)
        assertEquals("Aspirin", result.drug.drugName)
        assertEquals(SAVED_ID, result.drug.drugId)
    }

    @Test
    fun `unknown drug without nameIfUnknown is NotFound`() = runTest {
        coEvery { drugRepository.getDrugInfoByNdc(any()) } returns null

        assertEquals(DrugLookup.NotFound, resolver.resolve(NDC, nameIfMissing = "Unknown Drug"))
        coVerify(exactly = 0) { drugMasterDao.upsertPreservingId(any()) }
    }

    @Test
    fun `server error is Failed`() = runTest {
        val boom = RuntimeException("network")
        coEvery { drugRepository.getDrugInfoByNdc(any()) } throws boom

        assertEquals(DrugLookup.Failed(boom), resolver.resolve(NDC))
    }

    private fun savedDrug(ndc: String, name: String = "Aspirin") =
        DrugMasterEntity(drugId = 5L, ndc = ndc, drugName = name, packageQty = 100, createdAt = 1L)

    private fun serverInfo(
        genericName: String? = "Aspirin",
        ndc: String = NDC,
        isEquivalent: Boolean = false,
    ) = DrugInfo(
        brandName = "Bayer", genericName = genericName, ndc = ndc, is_drug_equivalent = isEquivalent,
        drugType = "CII", qty = 100, isHazardous = true, strength = "81 mg", dosageForm = "TABLET",
        imageUrl = IMAGE_URL,
    )

    private companion object {
        const val GTIN = "00368540100014"
        const val NDC = "68540-100-01"
        const val SAVED_ID = 42L
        const val IMAGE_URL = "https://img/aspirin.webp"
        const val IMAGE_PATH = "/files/drug_images/aspirin.webp"
    }
}
