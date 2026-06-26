package com.rite.pillcounting.feature.profile.domain.model

import androidx.annotation.StringRes
import com.rite.pillcounting.R

/**
 * Pharmacy types selectable in the profile screen.
 *
 * [apiValue] is the value sent to the backend in the `pharmacy_type` field of the
 * profile update request; [labelRes] is the user-facing display label.
 */
enum class PharmacyType(val apiValue: String, @StringRes val labelRes: Int) {
    CHAIN("chain_pharmacy", R.string.pharmacy_type_chain),
    SUPERMARKET_BIG_BOX("supermarket_big_box_pharmacy", R.string.pharmacy_type_supermarket),
    INDEPENDENT("independent_pharmacy", R.string.pharmacy_type_independent),
    MAIL_ORDER_ONLINE("mail_order_online_pharmacy", R.string.pharmacy_type_mail_order),
    HOSPITAL("hospital_pharmacy", R.string.pharmacy_type_hospital),
    CLINIC("clinic_pharmacy", R.string.pharmacy_type_clinic),
    LONG_TERM_CARE("long_term_care_pharmacy", R.string.pharmacy_type_long_term_care),
    COMPOUNDING("compounding_pharmacy", R.string.pharmacy_type_compounding),
    SPECIALTY("specialty_pharmacy", R.string.pharmacy_type_specialty);

    companion object {
        fun fromApiValue(value: String?): PharmacyType? =
            entries.firstOrNull { it.apiValue == value }
    }
}
