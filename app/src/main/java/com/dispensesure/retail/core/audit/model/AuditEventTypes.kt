package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.Serializable

/** One field's before / after, used by every "old → new" event. */
@Serializable
data class FieldChange(val field: String, val old: String?, val new: String?)

/** Whether an HL7 send was the first try or a resend. */
@Serializable
enum class SendTrigger { FIRST_SEND, AUTO_RESEND }
