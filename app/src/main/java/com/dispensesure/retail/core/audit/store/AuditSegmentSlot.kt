package com.dispensesure.retail.core.audit.store

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig
import java.time.LocalDate

/** The slot the writer appends to. Saved before its file is made, so a kill never hands a slot out twice. */
interface AuditSegmentSlot {
    fun load(): SegmentId?
    fun save(segmentId: SegmentId)
}

class PrefsAuditSegmentSlot(context: Context) : AuditSegmentSlot {

    private val prefs = context.getSharedPreferences(AuditLogConfig.SEQUENCE_PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SegmentId? {
        val logDate = prefs.getString(KEY_LOG_DATE, null) ?: return null
        return SegmentId(LocalDate.parse(logDate), prefs.getInt(KEY_SEQUENCE, 1))
    }

    override fun save(segmentId: SegmentId) {
        // commit, not apply: the slot must be on disk before its file is created.
        prefs.edit()
            .putString(KEY_LOG_DATE, segmentId.logDate.toString())
            .putInt(KEY_SEQUENCE, segmentId.sequence)
            .commit()
    }

    private companion object {
        const val KEY_LOG_DATE = "segment_log_date"
        const val KEY_SEQUENCE = "segment_sequence"
    }
}
