package com.dispensesure.retail.feature.dispense.data

import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

private const val GTIN = "00368540100014"
private const val SERVER_IMAGE_URL = "https://images.example/drug.png"

/** Unit tests for [ContainerVerifier]. */
class ContainerVerifierTest {

    private val drugResolver: DrugResolver = mockk()
    private val verifier = ContainerVerifier(drugResolver)

    private fun drug(ndc: String = "NDC123") = DrugMasterEntity(drugId = 10L, drugName = "Aspirin", ndc = ndc)

    private fun resolvesTo(lookup: DrugLookup) {
        coEvery { drugResolver.resolve(any(), any(), any(), any(), any()) } returns lookup
    }

    @Test
    fun `the scan is looked up with the expected NDC, the unknown-drug name and the trimmed GTIN`() = runTest {
        resolvesTo(DrugLookup.Found(drug(), fromServer = false))

        verifier.verify(" $GTIN ", expectedNdc = "NDC123")

        coVerify {
            drugResolver.resolve(
                code = " $GTIN ",
                expectedNdc = "NDC123",
                allowNoName = true,
                nameIfUnknown = null,
                scannedGtin = GTIN,
            )
        }
    }

    @Test
    fun `a saved drug record is trusted as it is`() = runTest {
        val savedDrug = drug()
        resolvesTo(DrugLookup.Found(savedDrug, fromServer = false))

        assertEquals(ContainerOutcome.SavedMatch(savedDrug), verifier.verify(GTIN, "NDC123"))
    }

    @Test
    fun `the server confirming the expected NDC is a server match with its image`() = runTest {
        val serverDrug = drug(ndc = "NDC123")
        resolvesTo(DrugLookup.Found(serverDrug, fromServer = true, imageUrl = SERVER_IMAGE_URL))

        assertEquals(ContainerOutcome.ServerMatch(serverDrug, SERVER_IMAGE_URL), verifier.verify(GTIN, "NDC123"))
    }

    @Test
    fun `with no expected NDC any server drug matches`() = runTest {
        val serverDrug = drug(ndc = "OTHER")
        resolvesTo(DrugLookup.Found(serverDrug, fromServer = true))

        assertEquals(ContainerOutcome.ServerMatch(serverDrug, null), verifier.verify(GTIN, expectedNdc = null))
        assertEquals(ContainerOutcome.ServerMatch(serverDrug, null), verifier.verify(GTIN, expectedNdc = "  "))
    }

    @Test
    fun `a server-flagged equivalent is a substitute even with a different NDC`() = runTest {
        val equivalentDrug = drug(ndc = "SUBNDC")
        resolvesTo(DrugLookup.Found(equivalentDrug, fromServer = true, isEquivalent = true, imageUrl = SERVER_IMAGE_URL))

        assertEquals(ContainerOutcome.Substitute(equivalentDrug, SERVER_IMAGE_URL), verifier.verify(GTIN, "NDC123"))
    }

    @Test
    fun `a different server drug that is not an equivalent is a mismatch`() = runTest {
        val otherDrug = drug(ndc = "DIFFERENT")
        resolvesTo(DrugLookup.Found(otherDrug, fromServer = true))

        assertEquals(ContainerOutcome.Mismatch(otherDrug), verifier.verify(GTIN, "NDC123"))
    }

    @Test
    fun `no name and unknown drug are both not found`() = runTest {
        resolvesTo(DrugLookup.NoName)
        assertEquals(ContainerOutcome.NotFound, verifier.verify(GTIN, "NDC123"))

        resolvesTo(DrugLookup.NotFound)
        assertEquals(ContainerOutcome.NotFound, verifier.verify(GTIN, "NDC123"))
    }

    @Test
    fun `a failed lookup is returned with its error, not thrown`() = runTest {
        val serverDown = IllegalStateException("server down")
        resolvesTo(DrugLookup.Failed(serverDown))

        val outcome = verifier.verify(GTIN, "NDC123")

        assertSame(serverDown, (outcome as ContainerOutcome.LookupFailed).error)
    }
}
