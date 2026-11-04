package com.dispensesure.retail.core.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** App-defined reason a login request failed; never the server's own message. */
@Serializable
enum class AuthErrorCode { HTTP_REJECTED, NETWORK_UNAVAILABLE, TIMEOUT, UNKNOWN, DEVICE_KEY_UNAVAILABLE, UNSUPPORTED_REGION }

@Serializable
enum class LogoutMode { SERVER, LOCAL }

@Serializable
enum class SessionEndReason {
    REFRESH_REJECTED,
    OFFLINE_LIMIT_REACHED,
    NOT_REMEMBERED_ON_LAUNCH,
    EXPIRED_ON_LAUNCH,
    REGION_MISSING_ON_LAUNCH
}

fun authErrorCodeOf(throwable: Throwable): AuthErrorCode = when (throwable) {
    is SocketTimeoutException -> AuthErrorCode.TIMEOUT
    is IOException -> AuthErrorCode.NETWORK_UNAVAILABLE
    is HttpException -> AuthErrorCode.HTTP_REJECTED
    else -> AuthErrorCode.UNKNOWN
}

fun httpStatusOf(throwable: Throwable): Int? = (throwable as? HttpException)?.code()

@Serializable
@SerialName("OTP_REQUESTED")
data class OtpRequested(
    val email: String,
    @SerialName("is_resend") val isResend: Boolean
) : AuditLogEvent()

@Serializable
@SerialName("OTP_REQUEST_REJECTED")
data class OtpRequestRejected(
    val email: String,
    @SerialName("http_status") val httpStatus: Int?,
    @SerialName("error_code") val errorCode: AuthErrorCode
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}

@Serializable
@SerialName("LOGIN_SUCCEEDED")
data class LoginSucceeded(
    @SerialName("user_id") val userId: String?,
    val email: String,
    val role: String?,
    @SerialName("device_key") val deviceKey: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("remember_me") val rememberMe: Boolean
) : AuditLogEvent()

@Serializable
@SerialName("OTP_VERIFY_FAILED")
data class OtpVerifyFailed(
    val email: String,
    @SerialName("http_status") val httpStatus: Int?,
    @SerialName("error_code") val errorCode: AuthErrorCode
) : AuditLogEvent() {
    override val result: AuditResult get() = AuditResult.FAILURE
}

@Serializable
@SerialName("LOGOUT")
data class Logout(
    @SerialName("user_id") val userId: String?,
    val mode: LogoutMode
) : AuditLogEvent()

@Serializable
@SerialName("SESSION_ENDED")
data class SessionEnded(
    @SerialName("user_id") val userId: String?,
    val reason: SessionEndReason
) : AuditLogEvent()
