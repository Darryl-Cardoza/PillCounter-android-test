package com.rite.pillcounting.feature.dashboard.domain.model

/**
 * Represents the aggregated UI state for the Dashboard screen.
 *
 * This data class encapsulates both static dashboard metrics and dynamic states such as
 * user detail loading, error handling, navigation flags, and authentication-related feedback.
 *
 * All count values are [String] because they are intended for direct UI display.
 * The user detail is retrieved from the API and stored as a nullable [UserDetail] object.
 *
 * @property completedFixedCount Number of completed fixed count tasks.
 * @property partialFixedCount Number of in-progress or partial fixed count tasks.
 * @property completedRegularCount Number of completed regular count tasks.
 * @property partialRegularCount Number of in-progress or partial regular count tasks.
 * @property isLoadingUserDetail Whether the user detail API is currently loading.
 * @property userDetailError Error message from user detail fetch operation, if any.
 * @property userDetail Authenticated user's profile detail.
 * @property navigateToProfile Navigation flag to redirect user to profile completion screen
 *                              if their profile is incomplete or missing required fields.
 */
data class DashboardUiState(

    /** Number of fixed counts that have been fully completed. */
    val completedFixedCount: String = "0",

    /** Number of fixed counts that are in progress or partially completed. */
    val partialFixedCount: String = "0",

    /** Number of regular counts that have been fully completed. */
    val completedRegularCount: String = "0",

    /** Number of regular counts that are in progress or partially completed. */
    val partialRegularCount: String = "0",

    /** Indicates whether the user detail is currently being loaded from the server. */
    val isLoadingUserDetail: Boolean = false,

    /** Holds the error message if fetching user detail fails. Null if no error. */
    val userDetailError: String? = null,

    /** Represents the currently authenticated user's details. */
    val userDetail: UserDetail? = null,

    /** Triggers navigation to Profile screen if profile details are incomplete. */
    val navigateToProfile: Boolean = false,
    val logoutUser: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    //set as null because when we set 0 because of observer its consider the Id and redirect to barcode screen
    val createdBatchId: Long? = null
)
