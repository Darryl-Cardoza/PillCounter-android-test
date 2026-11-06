package com.dispensesure.retail.feature.hl7.parsing

import android.content.Context
import com.dispensesure.retail.R
import com.dispensesure.retail.core.region.RegionProvider
import com.dispensesure.retail.core.region.wording
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.scanning.data.DrugResolver
import com.dispensesure.retail.core.scanning.domain.model.DrugLookup
import com.dispensesure.retail.feature.hl7.notification.Hl7Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a drug code carried on an inbound HL7 order, and tells the operator when it cannot.
 *
 * Both inbound order paths — the standard ORC/RXE flow in `Hl7OrderProcessor` and the native ZDR
 * flow in `DispenseHl7Repository` — need the same thing: look the code up locally, fall back to
 * the drug-info API, and on failure show the region's wording for "drug not found". They each
 * held their own copy, so a change to one notification silently diverged from the other.
 */
@Singleton
class Hl7DrugResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drugResolver: DrugResolver,
    private val notifier: Hl7Notifier,
    private val regionProvider: RegionProvider,
) {

    /**
     * @param drugCode the NDC or DIN from the order.
     * @param drugName name to fall back on when the lookup has none; blank is treated as absent.
     * @return the resolved drug, or null after notifying the operator why it could not be found.
     */
    suspend fun resolve(drugCode: String, drugName: String?): DrugMasterEntity? {
        val name = drugName?.takeIf { it.isNotBlank() }
        val lookup = drugResolver.resolve(
            drugCode,
            expectedNdc = drugCode,
            nameIfMissing = name,
            nameIfUnknown = name,
        )
        return when (lookup) {
            is DrugLookup.Found -> lookup.drug
            is DrugLookup.Failed -> {
                notifyNotFound(R.string.hl7_notification_drug_not_found_api_failed, drugCode)
                null
            }
            DrugLookup.NoName, DrugLookup.NotFound -> {
                notifyNotFound(R.string.hl7_notification_drug_not_found_no_drug, drugCode)
                null
            }
        }
    }

    private fun notifyNotFound(messageRes: Int, drugCode: String) {
        notifier.show(
            title = context.getString(R.string.hl7_notification_drug_not_found_title),
            message = context.getString(regionProvider.current.wording(messageRes), drugCode),
        )
    }
}
