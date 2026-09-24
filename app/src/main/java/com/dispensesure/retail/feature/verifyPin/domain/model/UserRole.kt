package com.dispensesure.retail.feature.verifyPin.domain.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Represents the nested role object in [VerifiedUser].
 */
@JsonClass(generateAdapter = true)
data class UserRole(

    /** Role identifier */
    @Json(name = "_id")
    val id: String? = null,

    /** Role name, e.g., "admin" */
    @Json(name = "name")
    val name: String? = null
)
