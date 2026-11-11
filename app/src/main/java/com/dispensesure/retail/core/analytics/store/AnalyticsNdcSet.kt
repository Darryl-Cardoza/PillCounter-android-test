package com.dispensesure.retail.core.analytics.store

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON string-set encode/decode for the inventory NDC columns.
 *
 * `ndc_scanned` on the wire is the count of **distinct** drug codes scanned — five bottles of one
 * drug is 1, not 5 — so the row stores the codes themselves rather than a running counter. The
 * set is the only way to answer "distinct" across several transactions in the same day.
 *
 * Always encoded **sorted and de-duplicated**, so a row that is retried produces a byte-identical
 * body (design §4, `01-design.md:117`). A malformed or absent value decodes to an empty list
 * rather than throwing — a corrupt cell must not take down an upload.
 */
object AnalyticsNdcSet {

    private val serializer = ListSerializer(String.serializer())

    /** Decodes a stored JSON array. Returns empty for null, blank, or unparseable input. */
    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString(serializer, json) }.getOrDefault(emptyList())
    }

    /**
     * Encodes [values] sorted and de-duplicated, ignoring blanks.
     *
     * @return null when nothing remains, so an untouched column stays null rather than `"[]"`.
     */
    fun encodeSorted(values: Collection<String>): String? {
        val cleaned = values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        if (cleaned.isEmpty()) return null
        return Json.encodeToString(serializer, cleaned)
    }

    /**
     * Adds [value] to the set already encoded in [json].
     *
     * @return the new JSON, or [json] unchanged when [value] is blank or already present — so an
     *   unchanged row is not rewritten.
     */
    fun insert(json: String?, value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return json
        val existing = decode(json)
        if (existing.contains(trimmed)) return json
        return encodeSorted(existing + trimmed)
    }

    /** Number of distinct codes stored — what `ndc_scanned` sends. */
    fun size(json: String?): Int = decode(json).size
}
