package com.dispensesure.retail.core.models

import androidx.annotation.StringRes

/**
 * Represents the result of a validation operation, such as form input or user interaction checks.
 *
 * This model is typically used to encapsulate the success or failure of a validation check,
 * along with an optional error message for display in the UI.
 *
 * @property isSuccess Indicates whether the validation was successful.
 * @property errorMessageResId Optional string resource ID pointing to the validation error message.
 * Should be provided if [isSuccess] is false. Can be used with context.getString().
 */
data class ValidationResult(
    val isSuccess: Boolean,
    @StringRes val errorMessageResId: Int? = null
)
