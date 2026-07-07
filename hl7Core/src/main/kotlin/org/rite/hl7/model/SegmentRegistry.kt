package org.rite.hl7.model

import org.rite.hl7.model.ast.HL7Segment
import org.rite.hl7.model.segment.EQUSegment
import org.rite.hl7.model.segment.ERRSegment
import org.rite.hl7.model.segment.GenericSegment
import org.rite.hl7.model.segment.INVSegment
import org.rite.hl7.model.segment.MSASegment
import org.rite.hl7.model.segment.MSHSegment
import org.rite.hl7.model.segment.NTESegment
import org.rite.hl7.model.segment.OBXSegment
import org.rite.hl7.model.segment.ORCSegment
import org.rite.hl7.model.segment.PIDSegment
import org.rite.hl7.model.segment.PV1Segment
import org.rite.hl7.model.segment.QAKSegment
import org.rite.hl7.model.segment.QPDSegment
import org.rite.hl7.model.segment.RCPSegment
import org.rite.hl7.model.segment.RXCSegment
import org.rite.hl7.model.segment.RXDSegment
import org.rite.hl7.model.segment.RXESegment
import org.rite.hl7.model.segment.RXRSegment
import org.rite.hl7.model.segment.ZINSegment
import org.rite.hl7.model.segment.ZNISegment
import org.rite.hl7.model.segment.ZPRSegment

/**
 * Maps segment names to typed-view factories. Ships with all standard segments
 * pre-registered; custom (Z-)segments are added via [register]. Unknown segments
 * fall back to [GenericSegment] (lossless).
 */
class SegmentRegistry {

    private val definitions: MutableMap<String, SegmentDefinition> = mutableMapOf()

    init {
        listOf(
            MSHSegment.Definition, PIDSegment.Definition, PV1Segment.Definition,
            ORCSegment.Definition, MSASegment.Definition, ERRSegment.Definition,
            NTESegment.Definition, RXESegment.Definition, RXDSegment.Definition,
            RXCSegment.Definition, RXRSegment.Definition, OBXSegment.Definition,
            EQUSegment.Definition, INVSegment.Definition, QPDSegment.Definition,
            RCPSegment.Definition, QAKSegment.Definition, ZINSegment.Definition,
            ZPRSegment.Definition, ZNISegment.Definition,
        ).forEach { register(it) }
    }

    /** Registers (or overrides) a custom segment definition. */
    fun register(definition: SegmentDefinition) {
        definitions[definition.name] = definition
    }

    /** Wraps a generic segment into its typed view, or [GenericSegment] if unregistered. */
    fun wrap(raw: HL7Segment): TypedSegment =
        definitions[raw.name]?.factory?.invoke(raw) ?: GenericSegment(raw)

    fun isRegistered(name: String): Boolean = definitions.containsKey(name)
}
