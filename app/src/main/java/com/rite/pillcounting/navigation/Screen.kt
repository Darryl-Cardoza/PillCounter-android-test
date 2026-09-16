

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.rite.pillcounting.core.room.models.enums.ScanType
import com.rite.pillcounting.feature.history.domain.model.HistoryMode

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

    data object Batch : Screen {
        private const val ROUTE_PREFIX = "batch"
        const val ARG_BATCH_ID = "batch_id"

        override val route: String = "$ROUTE_PREFIX?$ARG_BATCH_ID={$ARG_BATCH_ID}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_BATCH_ID) {
                type = NavType.LongType
                defaultValue = 0L
            }
        )

        /** Navigate to a specific batch; batchId = 0 means "resolve latest batch". */
        fun createRoute(batchId: Long = 0L): String = "$ROUTE_PREFIX?$ARG_BATCH_ID=$batchId"
    }

    data object InventoryScan : Screen {
        private const val ROUTE_PREFIX = "inventory_scan"
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
         *    lazily on the first NDC scan inside `InventoryScanViewModel`.
         */
        fun createRoute(batchId: Long = 0L, bucketId: String? = null): String =
            "$ROUTE_PREFIX?$ARG_BATCH_ID=$batchId&$ARG_BUCKET_ID=${bucketId.orEmpty()}"
    }

    data object HistoryDetail : Screen {
        override val route: String = "history_detail"
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

        // Full route with query parameters
        override val route: String = "$ROUTE_PREFIX?$ARG_EMAIL={$ARG_EMAIL}"

        // List of arguments to parse from the NavBackStackEntry
        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_EMAIL) { type = NavType.StringType }
        )

        // Helper function to generate the route string
        fun createRoute(email: String): String {
            return "$ROUTE_PREFIX?$ARG_EMAIL=$email"
        }
    }


    data object ScanBarcode : Screen {
        private const val ROUTE_PREFIX = "scan_barcode"

        const val ARG_TYPE = "type"
        const val ARG_BATCH_ID = "batch_id"
        const val TXN_SCAN_TYPE = "txn_scan_type"

        override val route: String =
            "$ROUTE_PREFIX/{$ARG_TYPE}/{$ARG_BATCH_ID}?$TXN_SCAN_TYPE={$TXN_SCAN_TYPE}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TYPE) {
                type = NavType.StringType
            },
            navArgument(ARG_BATCH_ID) {
                type = NavType.LongType
            },
            navArgument(TXN_SCAN_TYPE) {
                type = NavType.StringType
                defaultValue = ScanType.BARCODE.name
            }
        )

        fun createRoute(
            scanType: String,
            txnScanType: ScanType,
            batchId: Long
        ): String {
            return "$ROUTE_PREFIX/$scanType/$batchId?$TXN_SCAN_TYPE=${txnScanType.name}"
        }
    }

    data object PillCount : Screen {
        private const val ROUTE_PREFIX = "pill_count"
        const val ARG_TYPE = "type"

        override val route: String = "$ROUTE_PREFIX/{$ARG_TYPE}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TYPE) { type = NavType.StringType }
        )

        fun createRoute(scanType: String) = "$ROUTE_PREFIX/$scanType"
    }

    // Merged single-screen flow for the dispense use case.
    // Combines RX scan, NDC scan, and pill counting on one screen.
    //
    // The optional `from_hl7` query flag tells the screen to start at PRE_NDC
    // and hydrate from the existing PMS-created transaction (drugName,
    // hl7-expected NDC, targetCount, rxNo are all pre-populated, RX scan is
    // skipped entirely).
    //
    // The optional `batch_id` is used in the stock count (REGULAR) flow to
    // associate the new transaction with the active batch.
    data object DispenseFlow : Screen {
        private const val ROUTE_PREFIX = "dispense_flow"
        const val ARG_TYPE = "type"
        const val ARG_FROM_HL7 = "from_hl7"
        const val ARG_FROM_RESUME = "from_resume"
        const val ARG_BATCH_ID = "batch_id"
        const val ARG_BUCKET_ID = "bucket_id"
        const val ARG_FROM_QUEUE = "from_queue"
        const val ARG_ALLOWED_NDCS = "allowed_ndcs"

        /**
         * SavedStateHandle key used by DispenseFlow to publish a lazily-created
         * stock-count batchId to the previous back-stack entry (InventoryScanHost).
         * The host consumes it on resume so its list re-subscribes to the real
         * batch instead of the initial 0L nav argument.
         */
        const val NAV_KEY_STOCK_COUNT_BATCH_ID = "stock_count_batch_id"

        override val route: String =
            "$ROUTE_PREFIX/{$ARG_TYPE}?$ARG_FROM_HL7={$ARG_FROM_HL7}" +
                "&$ARG_FROM_RESUME={$ARG_FROM_RESUME}&$ARG_BATCH_ID={$ARG_BATCH_ID}" +
                "&$ARG_BUCKET_ID={$ARG_BUCKET_ID}&$ARG_FROM_QUEUE={$ARG_FROM_QUEUE}" +
                "&$ARG_ALLOWED_NDCS={$ARG_ALLOWED_NDCS}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TYPE) { type = NavType.StringType },
            navArgument(ARG_FROM_HL7) {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument(ARG_FROM_RESUME) {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument(ARG_BATCH_ID) {
                type = NavType.LongType
                defaultValue = 0L
            },
            navArgument(ARG_BUCKET_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument(ARG_FROM_QUEUE) {
                type = NavType.BoolType
                defaultValue = false
            },
            navArgument(ARG_ALLOWED_NDCS) {
                type = NavType.StringType
                defaultValue = ""
            },
        )

        fun createRoute(
            scanType: String,
            fromHl7: Boolean = false,
            fromResume: Boolean = false,
            batchId: Long = 0L,
            bucketId: String? = null,
            fromQueue: Boolean = false,
            allowedNdcs: Set<String> = emptySet(),
        ) = "$ROUTE_PREFIX/$scanType?$ARG_FROM_HL7=$fromHl7" +
            "&$ARG_FROM_RESUME=$fromResume&$ARG_BATCH_ID=$batchId" +
            "&$ARG_BUCKET_ID=${bucketId.orEmpty()}&$ARG_FROM_QUEUE=$fromQueue" +
            "&$ARG_ALLOWED_NDCS=${allowedNdcs.joinToString(",")}"
    }

    data object SaveHistoryFor : Screen {
        override val route: String = "save_history_for"
    }

    data object RequireDoubleCount : Screen {
        override val route: String = "require_double_count"
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



