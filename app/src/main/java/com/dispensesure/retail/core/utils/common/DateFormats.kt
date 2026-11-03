package com.dispensesure.retail.core.utils.common

object DateFormats {

    // Shown on screen
    const val MM_DD_YY = "MM/dd/yy"
    const val MM_DD_YY_HH_MM_A = "MM/dd/yy hh:mm a"
    const val HH_MM_A = "hh:mm a"

    // PDF reports
    const val MMM_DD_YYYY = "MMM dd, yyyy"
    const val MMM_DD_YYYY_HH_MM_A = "MMM dd, yyyy hh:mm a"
    const val YYYY_MM_DD = "yyyy-MM-dd"
    const val YYYY_MM_DD_HH_MM_A = "yyyy-MM-dd hh:mm a"

    // Expiry is saved in this format. PMS and HL7 read it. Do not change the value.
    const val EXPIRY_STORED = "MM-dd-yyyy"

    // HL7, barcodes, server, logs and file names read these. Do not change the values.
    const val HL7_TIMESTAMP = "yyyyMMddHHmmss"
    const val GS1_YYMMDD = "yyMMdd"
    const val ISO_UTC_NO_SUFFIX = "yyyy-MM-dd'T'HH:mm:ss"
    const val LOG_TIMESTAMP = "yyyy-MM-dd HH:mm:ss.SSS"
    const val FILE_TIMESTAMP = "yyyyMMdd_HHmmss"
    const val IMAGE_FILE_TIMESTAMP = "yyyy-MM-dd_HH-mm-ss"

    // Shapes a saved expiry string can have
    val INPUT_FORMATS = listOf(
        EXPIRY_STORED
    )
}
