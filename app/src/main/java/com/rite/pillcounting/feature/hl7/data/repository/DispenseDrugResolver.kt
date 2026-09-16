package com.rite.pillcounting.feature.hl7.data.repository

import android.content.Context
import com.rite.pillcounting.R
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.scanning.data.DrugImageDownloader
import com.rite.pillcounting.core.scanning.data.DrugRepository
import com.rite.pillcounting.core.scanning.domain.model.DrugInfo
import com.rite.pillcounting.core.scanning.domain.model.GetNdcRequestModel
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.feature.hl7.notification.Hl7Notifier

/**
 * Shared "resolve drug locally or via API" logic for inbound HL7 dispense requests.
 *
 * Extracted out of [Hl7Repository] because the exact same local-DB-first / API-fallback
 * flow was duplicated across handleRdeDispenseRequest, handleOrderPacketDispenseRequest,
 * and handleZuiOrderPacketDispenseRequest. Behavior (log messages, notification copy,
 * field mapping, and the image-download-on-RDE-only distinction) is unchanged from the
 * original inline code — this is a pure structural move, instantiated internally by
 * [Hl7Repository] (not DI-injected) so its collaborators and logger tag stay identical.
 */
internal class DispenseDrugResolver(
    private val drugMasterDao: DrugMasterDao,
    private val drugRepository: DrugRepository,
    private val notifier: Hl7Notifier,
    private val context: Context,
    private val drugImageDownloader: DrugImageDownloader,
    private val logger: AppLogger,
) {

    /**
     * Outcome of [resolve]: either a (possibly null) resolved drug to proceed with, or a
     * signal that the caller must abort processing the inbound message exactly as the
     * original inline code did via an early `return` (a notification has already been
     * shown in that case).
     */
    sealed class Resolution {
        data class Success(val drug: DrugMasterEntity?) : Resolution()
        object Aborted : Resolution()
    }

    /**
     * Resolves [hl7Ndc] to a drug for an inbound dispense request: checks the local DB
     * first, then falls back to the drug-info API. On API failure, or when the API
     * returns no usable drug name, shows the "drug not found" notification and returns
     * [Resolution.Aborted] — callers must stop processing the message at that point
     * without creating/updating a transaction.
     *
     * When [downloadImage] is true (RDE flow only — the order-packet flows never fetched
     * an image here), also downloads and saves the resolved drug's image.
     */
    suspend fun resolve(hl7Ndc: String, downloadImage: Boolean): Resolution {
        // Local-first check
        val localDrug = drugMasterDao.getDrugByNdc(hl7Ndc)

        if (localDrug != null) {
            logger.i("Drug found in local DB for NDC: $hl7Ndc")
            return Resolution.Success(localDrug)
        }

        logger.i("Drug not found locally for NDC: $hl7Ndc, calling API")

        val request = GetNdcRequestModel(
            target_ndc = hl7Ndc,
            scanned_ndc = hl7Ndc
        )

        val drugInfo: DrugInfo? = try {
            drugRepository.getDrugInfoByNdc(request)
        } catch (e: Exception) {
            logger.e("Failed to fetch drug info from API for NDC: $hl7Ndc", e)
            notifier.show(
                title = context.getString(R.string.hl7_notification_drug_not_found_title),
                message = context.getString(R.string.hl7_notification_drug_not_found_api_failed, hl7Ndc)
            )
            return Resolution.Aborted
        }

        val resolvedNdc = drugInfo?.ndc?.takeIf { it.isNotBlank() }

        val resolvedDrugName = drugInfo?.genericName
            ?.takeIf { it.isNotBlank() }

        if (resolvedDrugName.isNullOrBlank()) {
            logger.w("No drug name resolved for NDC: $hl7Ndc")
            notifier.show(
                title = context.getString(R.string.hl7_notification_drug_not_found_title),
                message = context.getString(R.string.hl7_notification_drug_not_found_no_drug, hl7Ndc)
            )
            return Resolution.Aborted
        }

        val drug = resolvedNdc?.let {
            val imagePath = if (downloadImage) {
                drugImageDownloader.downloadAndSave(
                    url = drugInfo?.imageUrl,
                    drugName = resolvedDrugName
                )
            } else {
                null
            }
            DrugMasterEntity(
                ndc = it,
                drugName = resolvedDrugName,
                drugType = drugInfo?.drugType,
                isHazardous = drugInfo?.isHazardous ?: false,
                strength = drugInfo?.strength,
                dosageForm = drugInfo?.dosageForm,
                drugImagePath = imagePath,
            )
        }
        return Resolution.Success(drug)
    }
}
