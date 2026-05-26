package com.rite.pillcounting.feature.dispenseFlow.domain.model

import kotlinx.serialization.SerialName
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
    val package_ndc: String? = null,
    val product_ndc: String? = null,
    val splittable: Boolean? = null,
    val standard_name: String? = null,
    val active_ingredients: List<ActiveIngredient>? = null,
    val dea_schedule: String? = null,
    val dosage_form: String? = null,
    val lookup_name: String? = null,
    val manufacturer: String? = null,
    val route: List<String>? = null,
    val therapeutic_rxclass: TherapeuticRxClass? = null,
    val therapeutic_fda: TherapeuticFda? = null,
    val image: DrugImage? = null,
    val updated_at: String? = null,
    val `package`: Package? = null,
    val is_hazardous: Boolean? = null
)

@Serializable
data class ActiveIngredient(
    val name: String? = null,
    val strength: String? = null
)

@Serializable
data class TherapeuticRxClass(
    val primary_class: String? = null,
    val secondary_classes: List<String>? = null,
    val source: String? = null
)

@Serializable
data class TherapeuticFda(
    val note: String? = null
)

@Serializable
data class DrugImage(
    val link: String? = null
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
    val ndc: String? = null,
    val description: String? = null,
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