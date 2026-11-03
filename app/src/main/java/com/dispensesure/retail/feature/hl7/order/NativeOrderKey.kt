package com.dispensesure.retail.feature.hl7.order

import org.rite.hl7.model.segment.ZDRSegment

/**
 * Single source of truth for the identity of a DISPENSESURE_NATIVE (ZDR) order.
 *
 * The validator and every handler MUST resolve the key through [resolve] so they can
 * never disagree (a disagreement silently creates duplicate transactions).
 * Mirrors iOS `NativeOrderKey`.
 */
object NativeOrderKey {
    const val SEPARATOR = "-"

    /** Trims, strips leading '0' characters; blank/null/all-zero all map to "0" (first fill). */
    fun normalizedFillNo(fillNo: String?): String {
        val stripped = (fillNo ?: "").trim().trimStart('0')
        return stripped.ifEmpty { "0" }
    }

    /** `"<rx>-<normalizedFill>"`. */
    fun compose(rxNumber: String, fillNumber: String): String =
        "${rxNumber.trim()}$SEPARATOR${normalizedFillNo(fillNumber)}"

    /** ZDR-15 (orderReferenceNumber) when non-blank, otherwise [compose] of ZDR-14 and ZDR-17. */
    fun resolve(zdr: ZDRSegment): String {
        val explicit = zdr.orderReferenceNumber.trim()
        if (explicit.isNotEmpty()) return explicit
        return compose(zdr.rxNumber, zdr.fillNumber)
    }
}
