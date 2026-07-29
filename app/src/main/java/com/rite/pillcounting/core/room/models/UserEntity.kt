package com.rite.pillcounting.core.room.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.rite.pillcounting.core.security.SecureStringConverter
import com.rite.pillcounting.core.security.models.SecureString

/**
     * Room entity representing a user account, persisted locally for offline access.
     *
     * This entity mirrors the structure of the backend `profile` and `settings` payloads,
     * while also introducing local-only metadata such as [localId] and [createdAt].
     *
     * ### Key Notes
     * - [userId] (PK) is typically derived from the JWT claim; falls back to API `profile.user_id` or `profile.email`.
     * - Sensitive values like auth tokens are **not** stored here — those live in [androidx.room.Dao].
     * - Ensures a single canonical record per user account in local persistence.
     */
    @Entity(tableName = "users")
    data class UserEntity(

        @PrimaryKey(autoGenerate = true)
        val localId: Long = 0L,

        val userId: String,

        // ───── Profile fields ─────

        /** Email address of the user. */
        @TypeConverters(SecureStringConverter::class)
        val email: SecureString? = null,

        /** Full name of the user. */
        val fName: String? = null,
        val lName: String? = null,

        /** Contact phone number. */
        @TypeConverters(SecureStringConverter::class)
        val phoneNumber: SecureString? = null,

        /** Avatar/profile image URL. */
        val avatarUrl: String? = null,

        /** Role name (e.g., "admin", "pharmacist"). */
        val role: String? = null,

        /** Whether the user’s email/account is verified. */
        val isVerified: Boolean = false,

        /** Flag indicating if the profile is completed. */
        @ColumnInfo(name = "is_profile_completed")
        val isProfileCompleted: Boolean? = null,

        /** Associated pharmacy name (if applicable). */
        @ColumnInfo(name = "pharmacy_name")
        val pharmacyName: String? = null,

        /** NPI (National Provider Identifier) or equivalent ID. */
        @ColumnInfo(name = "npi_id")
        val npiId: String? = null,

        // ───── Settings fields ─────

        /** Preferred language code (e.g., "en", "hi"). */
        val language: String? = null,

        /** Preferred timezone (e.g., "Asia/Kolkata"). */
        val timezone: String? = null,

        /** Whether notifications are enabled. */
        val notifications: Boolean? = null,

        // ───── Local metadata ─────

        /**
         * Local timestamp (epoch millis) when this record was created/updated.
         */
        val createdAt: Long = System.currentTimeMillis(),
        val isHl7Enable: Boolean = false,

        /** Country code (e.g., "US", "CA"). */
        val country: String? = null,

        /** State/province code (e.g., "CA", "AB"). */
        val state: String? = null
    )
