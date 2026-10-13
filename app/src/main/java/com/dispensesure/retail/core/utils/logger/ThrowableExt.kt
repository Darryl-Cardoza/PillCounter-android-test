package com.dispensesure.retail.core.utils.logger

/**
 * A copy that keeps the exception type and stack frames but not the message, for logging where
 * the message can echo PHI (scanned labels, HL7 field values) that PhiRedactor would not catch.
 */
fun Throwable.withoutMessage(): Throwable = Exception(javaClass.name).also { it.stackTrace = stackTrace }