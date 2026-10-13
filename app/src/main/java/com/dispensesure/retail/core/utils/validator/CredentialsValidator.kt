package com.dispensesure.retail.core.utils.validator

import android.util.Patterns
import com.dispensesure.retail.R
import com.dispensesure.retail.core.models.ValidationResult
import javax.inject.Inject

/**
 * Provides validation utilities for user credentials and profile fields.
 *
 * Each method checks a single field and returns a [ValidationResult].
 * - Empty values are considered valid (optional fields).
 * - When invalid, a string resource ID for the error message is provided.
 *
 * @constructor Creates an instance of [CredentialsValidator].
 */
class CredentialsValidator @Inject constructor() {

    /**
     * Validates an email address format.
     *
     * @param email The email string to validate.
     * @return [ValidationResult] with success if blank or valid format,
     * failure if the email is incorrectly formatted.
     */
    fun validateEmail(email: String): ValidationResult {
        if (email.isBlank()) return ValidationResult(true) // optional
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return ValidationResult(false, R.string.error_email_invalid)
        }
        return ValidationResult(true)
    }

    /**
     * Validates a phone number format.
     *
     * Rules:
     * - Optional (blank values pass).
     * - Must match Android's [Patterns.PHONE].
     * - Must be at least 7 digits long.
     *
     * @param phone The phone number string to validate.
     * @return [ValidationResult] with success if blank or valid phone,
     * failure otherwise.
     */
    fun validatePhone(phone: String): ValidationResult {
        if (phone.isBlank()) return ValidationResult(true)
        if (!Patterns.PHONE.matcher(phone).matches() || phone.length < 7) {
            return ValidationResult(false, R.string.error_phone_invalid)
        }
        return ValidationResult(true)
    }

    /**
     * Validates a person's name.
     *
     * Rules:
     * - Optional (blank passes).
     * - Must not contain digits.
     *
     * @param name The name string to validate.
     * @return [ValidationResult] with success if valid,
     * failure if digits are found.
     */
    fun validateRequiredName(name: String): ValidationResult {
        // A name made only of spaces, hyphens or apostrophes is as empty as a blank one.
        if (name.none { it.isLetter() }) {
            return ValidationResult(false, R.string.error_name_required)
        }
        if (name.any { it.isDigit() }) {
            return ValidationResult(false, R.string.error_name_invalid)
        }
        return ValidationResult(true)
    }

    /**
     * Validates a pharmacy name.
     *
     * Rules:
     * - Optional (blank passes).
     * - Must be at least 2 characters long.
     *
     * @param pharmacy The pharmacy name string to validate.
     * @return [ValidationResult] with success if valid,
     * failure if too short.
     */
    fun validatePharmacyName(pharmacy: String): ValidationResult {
        // Measured on the trimmed value: the field keeps a trailing space while
        // typing, and that space must not count towards the minimum.
        val trimmed = pharmacy.trim()
        if (trimmed.isEmpty()) return ValidationResult(true)
        if (trimmed.length < 2 || trimmed.none { it.isLetter() }) {
            return ValidationResult(false, R.string.error_pharmacy_name_invalid)
        }
        return ValidationResult(true)
    }

    companion object {
        private val DISALLOWED_NAME_CHARS = Regex("[^\\p{L} '-]")
        private val SPACE_RUN = Regex(" {2,}")

        /**
         * Keeps only letters, spaces, apostrophes and hyphens. Collapses double
         * spaces and drops a leading one; a trailing space is kept so the user
         * can type the next word.
         *
         * @param input Raw text currently in the field.
         * @return The same text with disallowed characters removed.
         *
         * Example Usage:
         * sanitizeName("Smith & Sons") // "Smith Sons"
         */
        fun sanitizeName(input: String): String =
            input.replace(DISALLOWED_NAME_CHARS, "")
                .replace(SPACE_RUN, " ")
                .removePrefix(" ")

        /**
         * Builds the key two enrolled people are considered the same by: the
         * full name, case- and space-insensitive.
         *
         * @param first First name as typed or scanned.
         * @param last Last name as typed or scanned.
         * @return A comparable key. The `\u0000` join keeps the two fields
         * distinct — [sanitizeName] can never produce it — so ("John Smith", "")
         * does not collide with ("John", "Smith").
         *
         * Example Usage:
         * normalizedNameKey(" john ", "SMITH") == normalizedNameKey("John", "Smith")
         */
        fun normalizedNameKey(first: String, last: String): String =
            "${first.trim().replace(SPACE_RUN, " ")}\u0000${last.trim().replace(SPACE_RUN, " ")}"
                .lowercase()

        /**
         * Checks a name against the people already enrolled.
         *
         * Rules:
         * - A half-typed name passes; presence is gated separately.
         * - Fails when the full combo is already in [takenNameKeys].
         *
         * @param first First name as typed or scanned.
         * @param last Last name as typed or scanned.
         * @param takenNameKeys [normalizedNameKey] of every enrolled profile.
         * @return [ValidationResult] with success if the name is free,
         * failure if someone is already enrolled under it.
         */
        fun validateNameNotTaken(
            first: String,
            last: String,
            takenNameKeys: Set<String>
        ): ValidationResult {
            if (first.isBlank() || last.isBlank()) return ValidationResult(true)
            if (normalizedNameKey(first, last) in takenNameKeys) {
                return ValidationResult(false, R.string.face_registration_name_taken)
            }
            return ValidationResult(true)
        }
    }
}
