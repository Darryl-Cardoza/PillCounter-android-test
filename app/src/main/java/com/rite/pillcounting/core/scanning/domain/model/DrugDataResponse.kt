package com.rite.pillcounting.core.scanning.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DrugDataResponse(
    val status: Int? = null,
    val is_success: Boolean? = null,
    val message: String? = null,
    val token: String? = null,
    val data: DrugComparisonData? = null
)

@Serializable
data class DrugComparisonData(
    val is_ndc_same: Boolean? = null,
    val is_ndc_equivalent: Boolean? = null,
    val target_ndc: NdcDrugInfo? = null,
    val scanned_ndc: NdcDrugInfo? = null
)

@Serializable
data class NdcDrugInfo(
    val drug_code: String? = null,
    val brand_name: String? = null,
    val generic_name: String? = null,
    val splittable: Boolean? = null,
    val standard_name: String? = null,
    val strength_info: StrengthInfo? = null,
    val active_ingredients: List<ActiveIngredient>? = null,
    val regulatory: DrugRegulatory? = null,
    val dosage_form: List<String>? = null,
    val lookup_name: String? = null,
    val manufacturer: String? = null,
    val route: List<String>? = null,
    val therapeutic: DrugTherapeutic? = null,
    val images: DrugImages? = null,
    val updated_at: String? = null,
    val `package`: Package? = null,
    val is_hazardous: Boolean? = null
)

/**
 * Pre-formatted strength of the drug as returned by the backend.
 *
 * @property display Human-readable strength to show in the UI, e.g. "100 MG".
 * @property value   Numeric strength value, e.g. 100.
 * @property unit    Strength unit, e.g. "MG".
 */
@Serializable
data class StrengthInfo(
    val display: String? = null,
    val value: Double? = null,
    val unit: String? = null
)

@Serializable
data class ActiveIngredient(
    val name: String? = null,
    val strength: String? = null,
    val strength_raw: String? = null,
    val strength_value: Double? = null,
    val strength_unit: String? = null,
    val ingredient_code: String? = null
)

@Serializable
data class DrugRegulatory(
    val schedule: String? = null,
    val is_controlled: Boolean? = null,
    val status: String? = null,
    val status_date: String? = null
)

@Serializable
data class DrugTherapeutic(
    val primary_class: String? = null,
    val secondary_classes: List<String>? = null,
    val rxclass_source: String? = null,
    val fda_note: String? = null,
    val atc_codes: List<String>? = null
)

@Serializable
data class DrugImages(
    val total: Int? = null,
    val primary: String? = null,
    val all: List<DrugImageItem>? = null
)

@Serializable
data class DrugImageItem(
    val url: String? = null,
    val filename: String? = null
)

/**
 * Represents a drug package with hierarchical levels and unit information.
 *
 * Example structure:
 * - CONTAINER (BOTTLE) containing
 *   - UNIT (TABLET) with modifiers
 */
@Serializable
data class Package(
    val description: String? = null,
    val container_type: String? = null,
    val stock_qty: Int? = null,
    val sizes: List<String>? = null,
    val levels: List<PackageLevel>? = null
)

/**
 * Represents a level within a package hierarchy.
 * Can be a container (e.g., BOTTLE) or unit (e.g., TABLET).
 */
@Serializable
data class PackageLevel(
    val type: String? = null,
    val name: String? = null,
    val quantity: Int? = null,
    val modifiers: List<String>? = null,
    val material: String? = null,
    val contains: PackageLevel? = null
)