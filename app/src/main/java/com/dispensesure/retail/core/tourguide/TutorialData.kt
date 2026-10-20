package com.dispensesure.retail.core.tourguide

import com.dispensesure.retail.core.region.Region
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.models.DrugMasterEntity

/** Durable markers for tutorial-created rows; DAO filters and the purge both key off these. */
object TutorialData {
    const val DEMO_NDC = "00000000000"
    const val BUCKET = "__TUTORIAL__"
    const val RX_PREFIX = "TUTORIALDEMO"

    /** Upserted in place every run, so repeat tours don't pile up `drug_master` rows. */
    suspend fun upsertDemoDrug(dao: DrugMasterDao, name: String, region: Region) {
        dao.upsertPreservingId(DrugMasterEntity(ndc = DEMO_NDC, drugName = name, drugType = "TABLET", region = region))
    }
}
