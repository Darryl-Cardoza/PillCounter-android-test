package com.rite.pillcounting.core.scanning.logic

/** A single OCR line with its glyph height, used to rank prominence on the card. */
data class IdTextLine(val text: String, val heightPx: Int = 0)

/** Name extracted from a photo ID (driver's license or staff badge). */
data class IdCardName(val firstName: String, val lastName: String)

/**
 * Heuristic name extraction from OCR'd photo-ID text.
 *
 * Strategy, in confidence order:
 *  1. Labeled AAMVA fields on a license front ("LN SMITH" / "FN JOHN",
 *     or the numbered "1 SMITH" / "2 JOHN" layout) — used only when BOTH
 *     labels are present, so a stray "1 MAIN ST" address line can't misfire.
 *  2. An explicit name label: "Name: Cole Paulson" inline, a bare
 *     "Name"/"Full Name" label whose value is on the NEXT line, or stacked
 *     "First Name" / "Last Name" labels each followed by their value.
 *  3. A "LAST, FIRST" comma line.
 *  4. The most prominent (tallest) line of 2–3 name-like words after
 *     filtering out role/org/license vocabulary — the badge case.
 *
 * Results prefill editable fields, so heuristics aim for "usually right",
 * not perfect.
 */
object IdNameParser {

    private val LAST_NAME_LABEL = Regex("""^(?:LN|1)[:.]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val FIRST_NAME_LABEL = Regex("""^(?:FN|2)[:.]?\s+(.+)$""", RegexOption.IGNORE_CASE)

    // Explicit printed labels; the value is inline after the label or on the next
    // line. \b keeps words that merely START with "name" ("Namesake…") from matching.
    private val FIRST_LABEL_LINE = Regex("""^FIRST\s*NAME\b[:.]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val LAST_LABEL_LINE = Regex("""^LAST\s*NAME\b[:.]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val FULL_NAME_LABEL_LINE = Regex("""^(?:FULL\s*)?NAME\b[:.]?\s*(.*)$""", RegexOption.IGNORE_CASE)

    private val NAME_WORD = Regex("""^[A-Za-z][A-Za-z'’-]*$""")

    /** Titles/credentials that may surround a name without disqualifying the line. */
    private val STRIPPED_TOKENS = setOf(
        "DR", "MR", "MRS", "MS", "MISS", "PROF",
        "RPH", "PHARMD", "PHD", "MD", "RN", "JR", "SR", "II", "III", "IV",
    )

    /** Vocabulary that marks a line as NOT a person's name. */
    private val EXCLUDED_WORDS = setOf(
        // Roles / org words (badges)
        "PHARMACIST", "PHARMACY", "TECHNICIAN", "TECH", "INTERN", "NURSE", "DOCTOR",
        "MEDICAL", "CENTER", "CENTRE", "HOSPITAL", "CLINIC", "HEALTH", "HEALTHCARE",
        "CARE", "STAFF", "EMPLOYEE", "BADGE", "DEPARTMENT", "DEPT", "ID",
        // License vocabulary (DL fronts)
        "DRIVER", "DRIVERS", "LICENSE", "LICENCE", "IDENTIFICATION", "PERMIT", "CARD",
        "STATE", "USA", "CLASS", "DOB", "EXP", "ISS", "SEX", "HGT", "WGT", "EYES",
        "HAIR", "DONOR", "VETERAN", "ORGAN", "RESTRICTIONS", "ENDORSEMENTS", "REV",
        // Field labels / address words / connectors
        "NAME", "FIRST", "LAST", "MIDDLE", "OF", "THE", "AND",
        "STREET", "AVENUE", "ROAD", "DRIVE", "LANE", "BLVD", "APT", "ST", "AVE", "RD",
    )

    fun parse(lines: List<IdTextLine>): IdCardName? {
        val cleaned = lines
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
        if (cleaned.isEmpty()) return null

        return labeledName(cleaned)
            ?: nameLabeledName(cleaned)
            ?: commaName(cleaned)
            ?: prominentName(cleaned)
    }

    /**
     * Every name-like word on the card, most prominent line first — shown to
     * the user as tap-to-fill suggestions when [parse]'s single best guess may
     * be wrong (badges with unusual layouts). Looser than [parse]: any line of
     * up to 4 clean words contributes, so multi-part names aren't dropped.
     */
    fun candidateWords(lines: List<IdTextLine>): List<String> {
        val cleaned = lines
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
        // Values sitting next to an explicit name label are the most likely
        // name words on the card — surface them ahead of everything else.
        val labeled = nameLabelValues(cleaned).flatMap { suggestionWords(it) }
        val byProminence = cleaned
            .sortedByDescending { it.heightPx }
            .flatMap { suggestionWords(it.text) }
        return (labeled + byProminence).distinct().take(MAX_SUGGESTIONS)
    }

    /**
     * Name-like words from one line, or empty if the line can't be part of a
     * name (too long, digits, or role/org/license vocabulary anywhere in it).
     */
    private fun suggestionWords(text: String): List<String> {
        val words = text.split(WHITESPACE)
            .map { it.trim('.', ',') }
            .filter { it.isNotEmpty() && it.uppercase() !in STRIPPED_TOKENS }
        if (words.size > 4) return emptyList()
        if (words.any { !NAME_WORD.matches(it) || it.uppercase() in EXCLUDED_WORDS }) return emptyList()
        return words.filter { it.length >= 2 }.map { displayCase(it) }
    }

    /** "COLE" → "Cole", "O'BRIEN" → "O'Brien", "SMITH-JONES" → "Smith-Jones". */
    fun displayCase(raw: String): String {
        val lower = raw.trim().lowercase()
        val sb = StringBuilder(lower.length)
        var capitalizeNext = true
        for (c in lower) {
            sb.append(if (capitalizeNext && c.isLetter()) c.uppercaseChar() else c)
            capitalizeNext = !c.isLetter()
        }
        return sb.toString()
    }

    private fun labeledName(lines: List<IdTextLine>): IdCardName? {
        val last = lines.firstNotNullOfOrNull { line ->
            LAST_NAME_LABEL.find(line.text)?.groupValues?.get(1)?.takeIf { isNameText(it) }
        }
        val first = lines.firstNotNullOfOrNull { line ->
            FIRST_NAME_LABEL.find(line.text)?.groupValues?.get(1)?.takeIf { isNameText(it) }
        }
        if (last == null || first == null) return null
        // The FN value may carry a middle name ("JOHN A") — keep only the first word.
        return IdCardName(
            firstName = displayCase(first.split(WHITESPACE).first()),
            lastName = displayCase(last),
        )
    }

    /**
     * A printed name label anywhere on the card: "Name: Cole Paulson" inline,
     * a bare "Name" whose value is the NEXT line, or stacked "First Name" /
     * "Last Name" labels each with an inline or next-line value.
     */
    private fun nameLabeledName(lines: List<IdTextLine>): IdCardName? {
        var first: String? = null
        var last: String? = null
        var full: String? = null
        lines.forEachIndexed { index, line ->
            val firstMatch = FIRST_LABEL_LINE.find(line.text)
            val lastMatch = LAST_LABEL_LINE.find(line.text)
            // FULL anchors at "NAME…", so it can't also match a FIRST/LAST label line.
            val fullMatch = if (firstMatch == null && lastMatch == null) {
                FULL_NAME_LABEL_LINE.find(line.text)
            } else null
            when {
                firstMatch != null && first == null ->
                    first = labelValue(lines, index, firstMatch.groupValues[1])?.takeIf { isNameText(it) }
                lastMatch != null && last == null ->
                    last = labelValue(lines, index, lastMatch.groupValues[1])?.takeIf { isNameText(it) }
                fullMatch != null && full == null ->
                    full = labelValue(lines, index, fullMatch.groupValues[1])
            }
        }
        if (first != null && last != null) {
            return IdCardName(
                firstName = displayCase(first!!.split(WHITESPACE).first()),
                lastName = displayCase(last!!),
            )
        }
        return full?.let { commaNameFromText(it) ?: multiWordName(it) }
    }

    /** Values adjacent to any printed name label, for suggestion ranking. */
    private fun nameLabelValues(lines: List<IdTextLine>): List<String> =
        lines.mapIndexedNotNull { index, line ->
            val match = FIRST_LABEL_LINE.find(line.text)
                ?: LAST_LABEL_LINE.find(line.text)
                ?: FULL_NAME_LABEL_LINE.find(line.text)
                ?: return@mapIndexedNotNull null
            labelValue(lines, index, match.groupValues[1])
        }

    /** Inline remainder of a label line, or the following line when the label stands alone. */
    private fun labelValue(lines: List<IdTextLine>, index: Int, inline: String): String? {
        val value = inline.trim().ifEmpty { lines.getOrNull(index + 1)?.text?.trim().orEmpty() }
        return value.ifEmpty { null }
    }

    private fun commaName(lines: List<IdTextLine>): IdCardName? =
        lines.firstNotNullOfOrNull { commaNameFromText(it.text) }

    private fun commaNameFromText(text: String): IdCardName? {
        val parts = text.split(',')
        if (parts.size != 2) return null
        val lastPart = parts[0].trim()
        val firstPart = parts[1].trim()
        if (!isNameText(lastPart) || !isNameText(firstPart)) return null
        // The first-name side may carry a middle name/initial — keep the first word.
        val firstWord = firstPart.split(WHITESPACE).first()
        if (firstWord.length < 2 || lastPart.length < 2) return null
        return IdCardName(
            firstName = displayCase(firstWord),
            lastName = displayCase(lastPart),
        )
    }

    /**
     * Lenient first+last extraction for a value that a name label vouches for:
     * up to 4 clean words, first word → first name, last word → last name.
     */
    private fun multiWordName(text: String): IdCardName? {
        val words = text.split(WHITESPACE)
            .map { it.trim('.', ',') }
            .filter { it.isNotEmpty() && it.uppercase() !in STRIPPED_TOKENS }
        if (words.size !in 2..4) return null
        if (words.any { !NAME_WORD.matches(it) || it.uppercase() in EXCLUDED_WORDS }) return null
        if (words.first().length < 2 || words.last().length < 2) return null
        return IdCardName(
            firstName = displayCase(words.first()),
            lastName = displayCase(words.last()),
        )
    }

    private fun prominentName(lines: List<IdTextLine>): IdCardName? {
        val best = lines
            .mapNotNull { line -> nameWords(line.text)?.let { line to it } }
            .maxByOrNull { (line, _) -> line.heightPx }
            ?: return null
        val words = best.second
        return IdCardName(
            firstName = displayCase(words.first()),
            lastName = displayCase(words.last()),
        )
    }

    /**
     * Tokenizes a line and returns its 2–3 name words (titles/credentials
     * stripped, single-letter middle initial allowed), or null if the line
     * can't be a person's name.
     */
    private fun nameWords(text: String): List<String>? {
        val words = text.split(WHITESPACE)
            .map { it.trim('.', ',') }
            .filter { it.isNotEmpty() && it.uppercase() !in STRIPPED_TOKENS }
        if (words.size !in 2..3) return null
        if (words.any { !NAME_WORD.matches(it) || it.uppercase() in EXCLUDED_WORDS }) return null
        // First and last words must be real names; only a middle token may be an initial.
        if (words.first().length < 2 || words.last().length < 2) return null
        return words
    }

    private fun isNameText(text: String): Boolean {
        val words = text.trim().split(WHITESPACE)
        return words.isNotEmpty() &&
            words.all { NAME_WORD.matches(it) && it.uppercase() !in EXCLUDED_WORDS }
    }

    private val WHITESPACE = Regex("""\s+""")

    private const val MAX_SUGGESTIONS = 8
}
