package com.dispensesure.retail.core.audit.store

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class InMemorySegmentSlot : AuditSegmentSlot {
    @Volatile private var segmentId: SegmentId? = null
    override fun load(): SegmentId? = segmentId
    override fun save(segmentId: SegmentId) { this.segmentId = segmentId }
}

class MutableTestClock(initial: Instant) : Clock() {
    @Volatile var instant: Instant = initial
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
}
