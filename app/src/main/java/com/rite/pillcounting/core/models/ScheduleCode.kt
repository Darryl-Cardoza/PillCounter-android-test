package com.rite.pillcounting.core.models

enum class ScheduleCode {
    CII,
    CIII,
    CIV,
    CV,
    CVI
}

/** True when `drug_master.drugType` matches a DEA controlled-substance schedule ([ScheduleCode]). */
fun isControlledDrugType(drugType: String?): Boolean {
    val code = drugType?.trim()?.uppercase() ?: return false
    return ScheduleCode.entries.any { it.name == code }
}
