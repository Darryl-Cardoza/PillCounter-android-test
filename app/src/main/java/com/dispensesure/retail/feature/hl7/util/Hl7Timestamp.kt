package com.dispensesure.retail.feature.hl7.util

import com.dispensesure.retail.core.utils.common.DateFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The current time in HL7's timestamp format, for the outbound builders' MSH-7 and segment times. */
internal fun hl7TimestampNow(): String =
    SimpleDateFormat(DateFormats.HL7_TIMESTAMP, Locale.US).format(Date())
