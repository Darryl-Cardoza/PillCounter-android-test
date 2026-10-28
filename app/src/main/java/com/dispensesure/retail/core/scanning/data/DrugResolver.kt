package com.dispensesure.retail.core.scanning.data

import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.domain.data.IDrugRepository
import com.dispensesure.retail.core.scanning.domain.model.DrugInfo
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.core.scanning.domain.model.GetNdcRequestModel
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Finds a drug for a scanned code or an order NDC: the saved copy first, the server when it's
 * missing or not the expected drug. Every server result is saved in full.
 */
class DrugResolver @Inject constructor(
    private val drugMasterDao: DrugMasterDao,
    private val drugRepository: IDrugRepository,
    private val drugImageDownloader: DrugImageDownloader,
) {

    private val logger = AppLogger.create<DrugResolver>()

    /**
     * @param code scanned GTIN-14 or NDC.
     * @param expectedNdc drug the order asks for; a saved drug with another NDC goes to the server.
     * @param nameIfMissing name to save when the server knows the drug but sends no name.
     * @param nameIfUnknown name to save a minimal record under when the server doesn't know the drug.
     * @param allowNoName with no name from the server or [nameIfMissing], save the drug without one.
     * @param scannedGtin GTIN saved on the drug so the next scan finds it locally.
     */
    suspend fun resolve(
        code: String,
        expectedNdc: String? = null,
        nameIfMissing: String? = null,
        nameIfUnknown: String? = null,
        allowNoName: Boolean = false,
        scannedGtin: String? = null,
    ): DrugLookup {
        val saved = listOfNotNull(drugMasterDao.getDrugByGtin(code), drugMasterDao.getDrugByNdc(code))
            .firstOrNull { !it.drugName.isNullOrBlank() }
        if (saved != null && (expectedNdc.isNullOrBlank() || saved.ndc == expectedNdc)) {
            return DrugLookup.Found(saved, fromServer = false)
        }
        return try {
            val info = drugRepository.getDrugInfoByNdc(
                GetNdcRequestModel(target_drug = expectedNdc.orEmpty(), scanned_drug = code)
            )
            if (info == null) saveUnknown(code, nameIfUnknown, scannedGtin)
            else saveFromServer(info, code, nameIfMissing, allowNoName, scannedGtin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e("Drug lookup failed for code=$code", e, event = LogEvent.DRUG_LOOKUP_FAILED)
            DrugLookup.Failed(e)
        }
    }

    private suspend fun saveFromServer(
        info: DrugInfo,
        code: String,
        nameIfMissing: String?,
        allowNoName: Boolean,
        scannedGtin: String?,
    ): DrugLookup {
        val name = info.genericName?.takeIf { it.isNotBlank() }
            ?: nameIfMissing?.takeIf { it.isNotBlank() }
        if (name == null && !allowNoName) return DrugLookup.NoName
        val ndc = info.ndc.ifBlank { code }
        // Image files are named after the drug, so use the NDC rather than a shared fallback name.
        val imagePath = drugImageDownloader.downloadAndSave(
            url = info.imageUrl,
            drugName = info.genericName?.takeIf { it.isNotBlank() } ?: ndc,
        )
        val drug = save(
            DrugMasterEntity(
                ndc = ndc,
                drugName = name,
                drugType = info.drugType,
                gtin = scannedGtin,
                packageQty = info.qty,
                isHazardous = info.isHazardous ?: false,
                strength = info.strength,
                dosageForm = info.dosageForm,
                drugImagePath = imagePath,
            )
        )
        return DrugLookup.Found(
            drug,
            fromServer = true,
            isEquivalent = info.is_drug_equivalent == true,
            imageUrl = info.imageUrl,
        )
    }

    private suspend fun saveUnknown(code: String, nameIfUnknown: String?, scannedGtin: String?): DrugLookup {
        val name = nameIfUnknown?.takeIf { it.isNotBlank() } ?: return DrugLookup.NotFound
        val drug = save(DrugMasterEntity(ndc = code, drugName = name, gtin = scannedGtin))
        return DrugLookup.Found(drug, fromServer = true)
    }

    private suspend fun save(drug: DrugMasterEntity): DrugMasterEntity =
        drug.copy(drugId = drugMasterDao.upsertPreservingId(drug))
}
