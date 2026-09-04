package org.rite.hl7.model

import org.rite.hl7.model.ast.HL7Segment

/**
 * Describes a custom (typically Z-) segment so the parser and builder can treat
 * it as a first-class typed segment without any core changes.
 *
 * A definition pairs the 3-char segment [name] with a [factory] that wraps a
 * generic [HL7Segment] into its typed view. Register on the parser/builder via
 * `registerCustomSegment(...)`.
 *
 * The built-in Z-segments (ZSN, ZSV, ZAD, ZIN, ZPR, ZNI) ship as ready-made
 * definitions (see their companion objects, e.g. [org.rite.hl7.model.segment.ZSNSegment.Definition]).
 */
open class SegmentDefinition(
    val name: String,
    val factory: (HL7Segment) -> TypedSegment,
)
