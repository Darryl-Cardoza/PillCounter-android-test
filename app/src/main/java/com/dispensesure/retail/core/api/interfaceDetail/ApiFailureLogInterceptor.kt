package com.dispensesure.retail.core.api.interfaceDetail

import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.core.utils.logger.AppLogger
import com.dispensesure.retail.core.utils.logger.LogEvent
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Central ERROR log for failed API requests: any non-2xx response or [IOException] on the main
 * client. Logs method + path only (no query string, no bodies) to keep PHI/device keys out of logs.
 *
 * Skipped on purpose: synthetic offline 599 (the health gate already logs it), 401 (the
 * authenticator logs the refresh outcome), cancelled calls, and `/mobile/logs` itself.
 */
class ApiFailureLogInterceptor : Interceptor {

    private val logger = AppLogger.create<ApiFailureLogInterceptor>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val skip = path.endsWith(URLConstant.MOBILE_LOGS)

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!skip && !chain.call().isCanceled()) {
                val event = if (e is SocketTimeoutException) LogEvent.NETWORK_TIMEOUT else LogEvent.NETWORK_ERROR
                logger.e("${request.method} $path failed: ${e.javaClass.simpleName}", e, event = event)
            }
            throw e
        }

        if (!skip && !response.isSuccessful && response.code != 401 && response.code != 599) {
            logger.e("${request.method} $path -> HTTP ${response.code}", event = LogEvent.NETWORK_ERROR)
        }
        return response
    }
}
