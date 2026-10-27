package com.dispensesure.retail.core.scanning.domain.model

import com.dispensesure.retail.core.room.models.DrugMasterEntity

/** Result of [com.dispensesure.retail.core.scanning.data.DrugResolver.resolve]. */
sealed interface DrugLookup {

    /** [imageUrl] is the server's image URL; set only when [fromServer] is true. */
    data class Found(
        val drug: DrugMasterEntity,
        val fromServer: Boolean,
        val isEquivalent: Boolean = false,
        val imageUrl: String? = null,
    ) : DrugLookup

    /** The server knows the drug but sent no name, and no fallback name was given. */
    data object NoName : DrugLookup

    /** The server doesn't know the drug, and no name was given to save it under. */
    data object NotFound : DrugLookup

    data class Failed(val error: Exception) : DrugLookup
}
