package com.rite.pillcounting.core.utils.constants

/**
 * Centralized container for miscellaneous app-wide literals that aren't API paths
 * ([URLConstant]) or dimensions ([Dimens]).
 */
object AppConstants {

    /** `platform` value sent on every request that reports the client platform. */
    const val PLATFORM_ANDROID = "android"

    /** Fallback terminal name used when a selected terminal has no name. */
    const val UNKNOWN_TERMINAL_NAME = "Unknown"
}
