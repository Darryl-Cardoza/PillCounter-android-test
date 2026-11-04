package com.dispensesure.retail.core.audit.store

import android.content.Context
import com.dispensesure.retail.core.audit.AuditLogConfig

/** Per-install record number; a gap in the file means a record was lost. Called from the audit worker only. */
class AuditSequenceCounter(context: Context) {

    private val prefs = context.getSharedPreferences(AuditLogConfig.SEQUENCE_PREFS_NAME, Context.MODE_PRIVATE)

    fun next(): Long {
        val nextSeq = prefs.getLong(KEY_LAST_SEQ, 0L) + 1
        // commit, not apply: a kill must not hand the same number out twice.
        prefs.edit().putLong(KEY_LAST_SEQ, nextSeq).commit()
        return nextSeq
    }

    private companion object {
        const val KEY_LAST_SEQ = "last_seq"
    }
}
