package com.dispensesure.retail.core.api.interfaceDetail

import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.ENDPOINT_FAULT_CODES
import com.dispensesure.retail.core.utils.logger.LogEvent
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Central ERROR log for failed API requests: any non-2xx response or [IOException] on the main
 * client. Logs method + path only (no query string, no bodies) to keep PHI/device keys out of logs.
 *
 * Levels: 5xx, wrong-endpoint 4xx (403, 404, 405, 410) and non-offline IO failures are ERROR; other
 * 4xx and offline failures (no host/connection) are WARN, being mostly expected (wrong OTP, no network).
 *
 * Skipped on purpose: synthetic offline 599 (the health gate already logs it), 401 (the
 * authenticator logs the refresh outcome), cancelled calls, `/mobile/logs` itself, and `/health`
 * and the token refresh, whose owners (SessionHealthController, TokenAuthenticator) log them.
 */
class ApiFailureLogInterceptor : Interceptor {

    private val logger = AppLogger.create<ApiFailureLogInterceptor>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val skip = path.endsWith(URLConstant.MOBILE_LOGS) ||
            path.endsWith(URLConstant.HEALTH) ||
            path.endsWith(URLConstant.REFRESH_TOKEN)

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!skip && !chain.call().isCanceled()) {
                val event = if (e is SocketTimeoutException) LogEvent.NETWORK_TIMEOUT else LogEvent.NETWORK_ERROR
                val message = "${request.method} $path failed: ${e.javaClass.simpleName}"
                // Offline, timeouts and resets are expected on a pharmacy network.
                logger.w(message, e, event = event)
            }
            throw e
        }

        if (!skip && !response.isSuccessful && response.code != 401 && response.code != 599) {
            val message = "${request.method} $path -> HTTP ${response.code}"
            if (response.code >= 500 || response.code in ENDPOINT_FAULT_CODES) logger.e(message, event = LogEvent.NETWORK_ERROR)
            else logger.w(message, event = LogEvent.NETWORK_ERROR)
        }
        return response
    }
}
