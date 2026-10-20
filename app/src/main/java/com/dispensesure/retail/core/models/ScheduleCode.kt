package com.dispensesure.retail.core.models

import com.dispensesure.retail.core.region.Region

/** Controlled-drug schedules: DEA for the US, CDSA classes for Canada. */
enum class ScheduleCode(val region: Region) {
    CII(Region.US),
    CIII(Region.US),
    CIV(Region.US),
    CV(Region.US),
    CVI(Region.US),

    /** Controlled Drug. */
    C(Region.CA),

    /** Narcotic. */
    N(Region.CA),

    /** Targeted Substance. */
    TS(Region.CA);

    companion object {
        /** One region's schedules, in display order. */
        fun forRegion(region: Region): List<ScheduleCode> = entries.filter { scheduleCode -> scheduleCode.region == region }
    }
}

/** True when `drug_master.drugType` is one of [region]'s controlled-drug schedules ([ScheduleCode]). */
fun isControlledDrugType(drugType: String?, region: Region): Boolean {
    val code = drugType?.trim()?.uppercase() ?: return false
    return ScheduleCode.forRegion(region).any { it.name == code }
}
