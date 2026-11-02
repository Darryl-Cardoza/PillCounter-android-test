package com.dispensesure.retail.navigation

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.dispensesure.retail.feature.history.domain.model.HistoryMode

/**
 * A sealed interfaceDetail to represent all navigable screens in the app.
 * This approach provides type safety and autocompletion for routes and arguments,
 * preventing common errors associated with string-based navigation.
 */
sealed interface Screen {
    val route: String

    // For screens without arguments
    data object Login : Screen {
        override val route: String = "login"
    }

    data object Dashboard : Screen {
        override val route: String = "dashboard"
    }

    data object Menu : Screen {
        override val route: String = "menu"
    }

    data object Settings : Screen {
        override val route: String = "settings"
    }

    data object StockCount : Screen {
        private const val ROUTE_PREFIX = "stock_count"
        const val ARG_BATCH_ID = "batch_id"
        const val ARG_BUCKET_ID = "bucket_id"

        override val route: String =
            "$ROUTE_PREFIX?$ARG_BATCH_ID={$ARG_BATCH_ID}&$ARG_BUCKET_ID={$ARG_BUCKET_ID}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_BATCH_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(ARG_BUCKET_ID) {
                type = NavType.StringType
                defaultValue = ""
                nullable = true
            }
        )

        /**
         * Entry-point route. Supply either:
         *  - [batchId] to resume an existing batch, OR
         *  - [bucketId] from the Inventory quick-action; the batch is created
         *    lazily on the first NDC scan inside `StockCountViewModel`.
         */
        fun createRoute(batchId: Long = 0L, bucketId: String? = null): String =
            "$ROUTE_PREFIX?$ARG_BATCH_ID=$batchId&$ARG_BUCKET_ID=${bucketId.orEmpty()}"
    }

    // Loose-pill count for a stock batch, opened from SCAN PILLS on the sealed-bottle screen.
    data object LooseCount : Screen {
        private const val ROUTE_PREFIX = "loose_count"
        const val ARG_BATCH_ID = "batch_id"
        const val ARG_BUCKET_ID = "bucket_id"
        const val ARG_ALLOWED_NDCS = "allowed_ndcs"

        // Set on the previous back-stack entry when a count is saved: the batch it went into.
        const val RESULT_BATCH_ID = "loose_count_batch_id"

        override val route: String =
            "$ROUTE_PREFIX?$ARG_BATCH_ID={$ARG_BATCH_ID}&$ARG_BUCKET_ID={$ARG_BUCKET_ID}&$ARG_ALLOWED_NDCS={$ARG_ALLOWED_NDCS}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_BATCH_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(ARG_BUCKET_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument(ARG_ALLOWED_NDCS) {
                type = NavType.StringType
                defaultValue = ""
            },
        )

        /**
         * [batchId] 0 = no batch yet; saving creates one in [bucketId]. A PMS batch passes
         * its requested NDCs in [allowedNdcs]; empty = any NDC.
         */
        fun createRoute(batchId: Long, bucketId: String?, allowedNdcs: Set<String>): String =
            "$ROUTE_PREFIX?$ARG_BATCH_ID=$batchId&$ARG_BUCKET_ID=${bucketId.orEmpty()}&$ARG_ALLOWED_NDCS=${allowedNdcs.joinToString(",")}"
    }

    data object HistoryDetail : Screen {
        private const val ROUTE_PREFIX = "history_detail"
        const val ARG_TXN_ID = "txn_id"

        override val route: String = "$ROUTE_PREFIX/{$ARG_TXN_ID}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TXN_ID) { type = NavType.LongType }
        )

        fun createRoute(txnId: Long): String = "$ROUTE_PREFIX/$txnId"
    }

    data object BatchHistoryDetail : Screen {
        private const val ROUTE_PREFIX = "batch_history_detail"
        const val ARG_BATCH_ID = "batch_id"

        override val route: String = "$ROUTE_PREFIX/{$ARG_BATCH_ID}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_BATCH_ID) { type = NavType.LongType }
        )

        fun createRoute(batchId: Long): String = "$ROUTE_PREFIX/$batchId"
    }

    data object Profile : Screen {
        override val route: String = "profile"
    }

    data object UnsyncedTransactionScreen : Screen {
        override val route: String = "unsynced_transaction_screen"
    }

    //For dispense and regular completed transaction
    data object History : Screen {

        private const val ROUTE_PREFIX = "history"
        const val ARG_TYPE = "type"

        // old route (normal history)
        override val route: String = "$ROUTE_PREFIX/{$ARG_TYPE}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TYPE) { type = NavType.StringType }
        )

        fun createRoute(type: HistoryMode) = "$ROUTE_PREFIX/${type.name}"
    }


    // For screens with arguments
    data object OtpVerify : Screen {
        private const val ROUTE_PREFIX = "otp_verify"
        const val ARG_EMAIL = "email"
        const val ARG_REMEMBER_ME = "remember_me"

        // Full route with query parameters
        override val route: String = "$ROUTE_PREFIX?$ARG_EMAIL={$ARG_EMAIL}&$ARG_REMEMBER_ME={$ARG_REMEMBER_ME}"

        // List of arguments to parse from the NavBackStackEntry
        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_EMAIL) { type = NavType.StringType },
            navArgument(ARG_REMEMBER_ME) {
                type = NavType.BoolType
                defaultValue = false
            }
        )

        // Helper function to generate the route string
        fun createRoute(email: String, rememberMe: Boolean): String {
            return "$ROUTE_PREFIX?$ARG_EMAIL=$email&$ARG_REMEMBER_ME=$rememberMe"
        }
    }


    // Merged single-screen flow for the dispense use case.
    // Combines RX scan, NDC scan, and pill counting on one screen.
    //
    // The optional `from_hl7` query flag tells the screen to start at PRE_NDC
    // and hydrate from the existing PMS-created transaction (drugName,
    // hl7-expected NDC, targetCount, rxNo are all pre-populated, RX scan is
    // skipped entirely).
    // `txn_id` names that transaction. Without `from_hl7`, a non-zero id resumes it.
    data object DispenseFlow : Screen {
        private const val ROUTE_PREFIX = "dispense_flow"
        const val ARG_TXN_ID = "txn_id"
        const val ARG_FROM_HL7 = "from_hl7"
        const val ARG_FROM_QUEUE = "from_queue"

        override val route: String =
            "$ROUTE_PREFIX?$ARG_TXN_ID={$ARG_TXN_ID}&$ARG_FROM_HL7={$ARG_FROM_HL7}&$ARG_FROM_QUEUE={$ARG_FROM_QUEUE}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TXN_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(ARG_FROM_HL7) {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument(ARG_FROM_QUEUE) {
                type = NavType.BoolType
                defaultValue = false
            },
        )

        fun createRoute(
            txnId: Long = 0L,
            fromHl7: Boolean = false,
            fromQueue: Boolean = false,
        ) = "$ROUTE_PREFIX?$ARG_TXN_ID=$txnId&$ARG_FROM_HL7=$fromHl7&$ARG_FROM_QUEUE=$fromQueue"
    }

    data object FaceIntro : Screen {
        override val route: String = "face_intro"
    }

    data object FaceRegistration : Screen {
        override val route: String = "face_registration"
    }

    data object FaceRecognitionUsers : Screen {
        override val route: String = "face_recognition_users"
    }

}



