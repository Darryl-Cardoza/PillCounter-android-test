package com.rite.pillcounting.core.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.rite.pillcounting.core.room.dao.BatchDao
import com.rite.pillcounting.core.room.dao.BottleInfoDao
import com.rite.pillcounting.core.room.dao.DrugMasterDao
import com.rite.pillcounting.core.room.dao.FaceEmbeddingDao
import com.rite.pillcounting.core.room.dao.FaceProfileDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDao
import com.rite.pillcounting.core.room.dao.PillCountTxnDetailsDao
import com.rite.pillcounting.core.room.dao.StockTxnDao
import com.rite.pillcounting.core.room.dao.UserDao
import com.rite.pillcounting.core.room.di.BatchConverters
import com.rite.pillcounting.core.room.di.StringListConverter
import com.rite.pillcounting.core.room.models.BatchEntity
import com.rite.pillcounting.core.room.models.BottleInfoEntity
import com.rite.pillcounting.core.room.models.DrugMasterEntity
import com.rite.pillcounting.core.room.models.FaceEmbeddingEntity
import com.rite.pillcounting.core.room.models.FaceProfileEntity
import com.rite.pillcounting.core.room.models.PillCountTxnDetailsEntity
import com.rite.pillcounting.core.room.models.PillCountTxnEntity
import com.rite.pillcounting.core.room.models.StockTxnEntity
import com.rite.pillcounting.core.room.models.UserEntity

/**
 * Main Room database for the application.
 *
 * This database defines all the core entities and their DAOs used across
 * the pill counting application.
 *
 * ### Entities:
 * - [UserEntity] → Represents application users.
 * - [DrugMasterEntity] → Master list of drugs with NDC and metadata.
 * - [PillCountTxnEntity] → Pill count transaction headers (parent records).
 * - [PillCountTxnDetailsEntity] → Pill count transaction details (child records).
 * - [BatchEntity] → Batch headers that group multiple transactions together.
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
        PillCountTxnEntity::class,
        PillCountTxnDetailsEntity::class,
        BatchEntity::class,
        StockTxnEntity::class,
        BottleInfoEntity::class,
        FaceProfileEntity::class,
        FaceEmbeddingEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(
    BatchConverters::class,
    StringListConverter::class
)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for accessing [UserEntity] records. */
    abstract fun userDao(): UserDao

    /** DAO for managing [DrugMasterEntity] records. */
    abstract fun drugMasterDao(): DrugMasterDao

    /** DAO for managing [PillCountTxnEntity] transaction headers. */
    abstract fun pillCountTxnDao(): PillCountTxnDao

    /** DAO for managing [PillCountTxnDetailsEntity] transaction details. */
    abstract fun pillCountTxnDetailsDao(): PillCountTxnDetailsDao

    /** DAO for managing [BatchEntity] batch headers. */
    abstract fun batchDao(): BatchDao

    /** DAO for managing [StockTxnEntity] stock transaction headers. */
    abstract fun stockTxnDao(): StockTxnDao

    /** DAO for managing [BottleInfoEntity] stock bottle lines. */
    abstract fun bottleInfoDao(): BottleInfoDao

    /** DAO for managing [FaceProfileEntity] records. */
    abstract fun faceProfileDao(): FaceProfileDao

    /** DAO for managing [FaceEmbeddingEntity] records. */
    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
}
