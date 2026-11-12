package com.dispensesure.retail.core.scanning.engine

import com.dispensesure.retail.core.region.Region

/** Drug and order text for the photo strip. The engine adds the count, user, location and time. */
data class PhotoLabel(
    val drugName: String?,
    val drugImagePath: String?,
    val isStockCount: Boolean,
    val requestedNdc: String?,
    val dispensedNdc: String?,
    val lotNumber: String?,
    val serialNumber: String?,
    val expirationDate: String?,
    val rxRefill: String?,
    val stepLabel: String,
    /** Decides the date format drawn on the strip. */
    val region: Region,
)
