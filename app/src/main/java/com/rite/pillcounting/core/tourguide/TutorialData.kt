package com.dispensesure.retail.core.tourguide

/** Durable markers for tutorial-created rows; DAO filters and the purge both key off these. */
object TutorialData {
    const val DEMO_NDC = "00000000000"
    const val DEMO_DRUG_NAME = "Tutorial Demo Drug"
    const val BUCKET = "TUTORIAL"
    const val RX_PREFIX = "TUTORIALDEMO"

    fun isTutorialRx(rxNo: String?): Boolean = rxNo?.startsWith(RX_PREFIX) == true
}
