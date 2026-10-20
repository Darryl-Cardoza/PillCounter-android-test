package com.dispensesure.retail.core.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dispensesure.retail.core.room.dao.StockBatchDao
import com.dispensesure.retail.core.room.dao.StockBottleInfoDao
import com.dispensesure.retail.core.room.dao.DrugMasterDao
import com.dispensesure.retail.core.room.dao.FaceEmbeddingDao
import com.dispensesure.retail.core.room.dao.FaceProfileDao
import com.dispensesure.retail.core.room.dao.DispenseTxnDao
import com.dispensesure.retail.core.room.dao.DispenseCountDao
import com.dispensesure.retail.core.room.dao.StockTxnDao
import com.dispensesure.retail.core.room.dao.UserDao
import com.dispensesure.retail.core.room.di.StockBatchConverters
import com.dispensesure.retail.core.room.di.StringListConverter
import com.dispensesure.retail.core.room.models.StockBatchEntity
import com.dispensesure.retail.core.room.models.StockBottleInfoEntity
import com.dispensesure.retail.core.room.models.DrugMasterEntity
import com.dispensesure.retail.core.room.models.FaceEmbeddingEntity
import com.dispensesure.retail.core.room.models.FaceProfileEntity
import com.dispensesure.retail.core.room.models.DispenseCountEntity
import com.dispensesure.retail.core.room.models.DispenseTxnEntity
import com.dispensesure.retail.core.room.models.StockTxnEntity
import com.dispensesure.retail.core.room.models.UserEntity

/**
 * Main Room database for the application.
 *
 * This database defines all the core entities and their DAOs used across
 * the pill counting application.
 *
 * ### Entities:
 * - [UserEntity] → Represents application users.
 * - [DrugMasterEntity] → Master list of drugs with NDC and metadata.
 * - [DispenseTxnEntity] → Dispense transaction headers (parent records).
 * - [DispenseCountEntity] → Dispense counts (child records).
 * - [StockBatchEntity] → Batch headers that group multiple transactions together.
 *
 * ### Usage:
 * Obtain an instance of [AppDatabase] via `Room.databaseBuilder()` and use
 * the exposed DAO getters to interact with persistent data.
 *
 * ### Notes:
 * - Increase the [android.R.attr.version] number and provide a migration strategy when making
 *   schema changes.
 * - `exportSchema = true` ensures schema history is exported for versioning.
 */
@Database(
    entities = [
        UserEntity::class,
        DrugMasterEntity::class,
        DispenseTxnEntity::class,
        DispenseCountEntity::class,
        StockBatchEntity::class,
        StockTxnEntity::class,
        StockBottleInfoEntity::class,
        FaceProfileEntity::class,
        FaceEmbeddingEntity::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(
    StockBatchConverters::class,
    StringListConverter::class
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for accessing [UserEntity] records. */
    abstract fun userDao(): UserDao

    /** DAO for managing [DrugMasterEntity] records. */
    abstract fun drugMasterDao(): DrugMasterDao

    /** DAO for managing [DispenseTxnEntity] transaction headers. */
    abstract fun dispenseTxnDao(): DispenseTxnDao

    /** DAO for managing [DispenseCountEntity] transaction details. */
    abstract fun dispenseCountDao(): DispenseCountDao

    /** DAO for managing [StockBatchEntity] batch headers. */
    abstract fun stockBatchDao(): StockBatchDao

    /** DAO for managing [StockTxnEntity] stock transaction headers. */
    abstract fun stockTxnDao(): StockTxnDao

    /** DAO for managing [StockBottleInfoEntity] stock bottle lines. */
    abstract fun stockBottleInfoDao(): StockBottleInfoDao

    /** DAO for managing [FaceProfileEntity] records. */
    abstract fun faceProfileDao(): FaceProfileDao

    /** DAO for managing [FaceEmbeddingEntity] records. */
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
}
