package com.rite.pillcounting.core.health.logic

import com.rite.pillcounting.core.health.domain.model.HealthState
import com.rite.pillcounting.core.utils.constants.URLConstant
import com.rite.pillcounting.core.utils.logger.AppLogger
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that short-circuits authed requests while the app is offline.
 *
 * Description:
 * Attached to the main OkHttp stack. When [SessionHealthController.state] is
 * anything other than `HEALTHY` (or `UNKNOWN`, which represents the pre-first-
 * check bootstrap window and is allowed to pass so cold-start `/health` +
 * settings can succeed), non-allowlisted requests are answered with a
 * synthetic HTTP `599` response so callers can distinguish "offline gate" from
 * a real network exception without adding a new exception type.
 *
 * Allowlist:
 * - `/health` — the recovery probe itself; must never be gated.
 * - `/mobile/get/settings` — bootstrap payload including the offline threshold.
 * - `/auth/refresh` — refresh must be able to run under `TokenAuthenticator`.
 * - `/auth/send/otp`, `/auth/verify/otp`, `/auth/logout` — auth entry / exit.
 */
@Singleton
class HealthGateInterceptor @Inject constructor(
    private val controller: SessionHealthController
) : Interceptor {

    private val logger = AppLogger.create<HealthGateInterceptor>()

    /**
     * Applies the offline gate to outgoing requests.
     *
     * What it does:
     * - Lets allowlisted paths through in every state.
     * - Lets any request through while state is `HEALTHY` or `UNKNOWN`.
     * - Returns a synthetic HTTP `599 "Offline"` response for everything else.
     *
     * @param chain The OkHttp interceptor chain.
     * @return The proceeded response, or a synthetic offline response.
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val allowlisted = isAllowlisted(path)

        // Outbound gate: block non-allowlisted requests when offline.
        if (!allowlisted) {
            when (controller.state.value) {
                HealthState.OFFLINE, HealthState.EXPIRED -> {
                    logger.w("Gate short-circuiting $path (state=${controller.state.value})")
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(599)
                        .message("Offline")
                        .body("".toResponseBody(null))
                        .build()
                }
                HealthState.HEALTHY, HealthState.UNKNOWN -> Unit
            }
        }

        // Inbound classifier: every failed request funnels through classifyAndReact so
        // OFFLINE flips are decided centrally rather than in every repo's catch block.
        // Allowlisted endpoints (/health itself, refresh, settings, auth) are excluded
        // because their outcomes drive the state machine directly.
        return try {
            val response = chain.proceed(request)
            if (!allowlisted && response.code in 500..599) {
                controller.classifyAndReact(null, response.code)
            }
            response
        } catch (e: IOException) {
            if (!allowlisted) {
                controller.classifyAndReact(e, null)
            }
            throw e
        }
    }

    /**
     * Returns true if [path] is exempt from the offline gate.
     *
     * @param path The request's encoded path (may or may not start with `/`).
     * @return true when the path matches one of the always-allowed endpoints.
     */
    private fun isAllowlisted(path: String): Boolean {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return normalized.endsWith(URLConstant.HEALTH) ||
            normalized.endsWith(URLConstant.MOBILE_SETTINGS) ||
            normalized.endsWith(URLConstant.REFRESH_TOKEN) ||
            normalized.endsWith(URLConstant.SEND_OTP) ||
            normalized.endsWith(URLConstant.VERIFY_OTP) ||
            normalized.endsWith(URLConstant.LOGOUT)
    }
}
