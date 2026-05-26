package com.rite.pillcounting.core.utils.compose

/**
 * @param fullWidth In multi-column layouts (e.g. the landscape stock-bottle
 * sheet), set to true to break out of the column grid and span the full
 * available width. Useful for long values like drug names that would otherwise
 * wrap awkwardly inside a half-width column. Ignored in single-column layouts.
 */
data class DialogField(
    val label: String,
    val value: String,
    val fullWidth: Boolean = false,
)

enum class ContainerStatus {
    SEALED,
    OPENED
}