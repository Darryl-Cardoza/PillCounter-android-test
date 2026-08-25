package com.rite.pillcounting.core.utils.constants

/**
 * Centralized container for all **API endpoint paths** and
 * global HTTP-related constants used throughout the application.
 */
object URLConstant {

    /** API endpoint for sending OTP to user's mobile or email. */
    const val SEND_OTP = "/auth/send/otp"

    /** API endpoint for verifying a previously sent OTP. */
    const val VERIFY_OTP = "/auth/verify/otp"

    /** API endpoint for logging the user out of the session. */
    const val LOGOUT = "/auth/logout"

    /** API endpoint for fetching authenticated user details (`/auth/me`). */
    const val GET_ABOUT_ME = "/auth/me"

    /** API endpoint for updating the user's profile information. */
    const val UPDATE_PROFILE = "/users/update/profile"

    /** API endpoint for deleting or deactivating the user's profile. */
    const val DELETE_PROFILE = "/users/delete/profile"

    /** API endpoint for retrieving mobile-specific configuration or branding data. */
    const val MOBILE_SETTINGS = "/mobile/get/settings"

    /** API endpoint for refreshing access and refresh tokens securely. */
    const val REFRESH_TOKEN = "/auth/refresh"

    /**
     * API endpoint for the backend health check.
     *
     * Called at three specific moments (never polled):
     *  1. App cold start (before the first `/auth/me` chain).
     *  2. Every foreground / `onResume` of the main activity.
     *  3. Immediately before every `/auth/me` invocation.
     *
     * A failing response flips the app-wide offline flag; a successful response
     * updates `lastHealthAt` and restores full API access.
     */
    const val HEALTH = "/health"

    /** API endpoint for updating terminal settings. The terminal ID is appended to the path. */
    const val UPDATE_TERMINAL = "/terminals/update/"

    /** API endpoint for listing terminals, optionally filtered to those available for claiming. */
    const val GET_TERMINALS_LIST = "/terminals/list"

    /** API endpoint for fetching the list of selectable pharmacy types. */
    const val GET_PHARMACY_TYPES = "/users/pharmacy-types"

    /** API endpoint for fetching the reference list of countries and their states/provinces. */
    const val GET_COUNTRIES = "/reference/countries"

    /** Common HTTP Content-Type header value for all JSON-based API calls. */
    const val CONTENT_TYPE = "application/json"
}
