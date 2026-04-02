

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

    data object Register : Screen {
        override val route: String = "register"
    }

    data object ForgotPassword : Screen {
        override val route: String = "forgot_password"
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

//    data object History : Screen {
//        override val route: String = "history"
//    }

    data object HistoryDetail : Screen {
        override val route: String = "history_detail"
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
        const val ARG_REMEMBER_ME = "rememberMe"

        // Full route with query parameters
        override val route: String =
            "$ROUTE_PREFIX?$ARG_EMAIL={$ARG_EMAIL}&$ARG_REMEMBER_ME={$ARG_REMEMBER_ME}"

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


    data object ScanBarcode : Screen {
        private const val ROUTE_PREFIX = "scan_barcode"
        const val ARG_TYPE = "type"
        const val TXN_SCAN_TYPE = "txn_scan_type"

        override val route: String =
            "$ROUTE_PREFIX/{$ARG_TYPE}?$TXN_SCAN_TYPE={$TXN_SCAN_TYPE}"

        val navArguments: List<NamedNavArgument> = listOf(
            navArgument(ARG_TYPE) {
                type = NavType.StringType
            },
            navArgument(TXN_SCAN_TYPE) {
                type = NavType.StringType
                defaultValue = ScanType.BARCODE.name
            }
        )

        fun createRoute(
            scanType: String,
            txnScanType: ScanType
        ): String {
            return "$ROUTE_PREFIX/$scanType?$TXN_SCAN_TYPE=${txnScanType.name}"
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

    data object ResumeFixedCounts : Screen {
        private const val ROUTE_PREFIX = "resume_fixed_counts"
        const val ARG_TYPE = "type"

        override val route: String = "$ROUTE_PREFIX/{$ARG_TYPE}"

        fun createRoute(type: String) = "$ROUTE_PREFIX/$type"
    }

    data object ResumeRegularCounts : Screen {
        private const val ROUTE_PREFIX = "resume_regular_counts"
        const val ARG_TYPE = "type"

        override val route: String = "$ROUTE_PREFIX/{$ARG_TYPE}"

        fun createRoute(type: String) = "$ROUTE_PREFIX/$type"
    }


}



