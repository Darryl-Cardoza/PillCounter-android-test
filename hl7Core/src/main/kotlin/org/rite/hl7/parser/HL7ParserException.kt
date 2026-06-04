package org.rite.hl7.parser

class Hl7ParseException(
    message: String,
    val segment: String? = null,
    field: String? = null,
    cause: Throwable? = null
) : Exception(
    "HL7 Parse Error: $message${segment?.let { " [Segment: $it]" } ?: ""}${field?.let { " [Field: $it]" } ?: ""}",
    cause
)
