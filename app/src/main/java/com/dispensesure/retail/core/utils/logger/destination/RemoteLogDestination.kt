package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.security.RuntimeUnit
import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogEventClassifier
import com.dispensesure.retail.core.utils.logger.LogLevel
import com.dispensesure.retail.core.utils.logger.PhiRedactor
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogError
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogNetwork
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogRequest
import com.dispensesure.retail.core.utils.logger.destination.remote.IRemoteLogApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ERROR-only feed that ships each entry to the app's `/mobile/logs` ingest endpoint (which
 * forwards it to Datadog), in the payload shape agreed with the backend team. Wired in by
 * [com.dispensesure.retail.core.utils.logger.AppLogger.init] alongside [FileLogDestination]; the
 * file destination keeps working unchanged regardless of what happens here — see
 * [CompositeLogDestination].
 *
 * Deliberately self-contained (its own [OkHttpClient]/[Retrofit], not the shared
 * `NetworkModule`/`HeaderInterceptor`), for two reasons:
 *  1. It is constructed directly in `AppLogger.init()`, which runs before the Hilt graph is
 *     guaranteed to be ready.
 *  2. A failure to retrieve the server key must never be logged back through `AppLogger` —
 *     that would route straight into this same destination and loop forever. Failures here are
 *     reported with plain [Log.w] instead.
 *
 * Fire-and-forget: [write] returns immediately and the POST runs on [scope]. Every failure
 * (key retrieval, network, serialization) is caught and swallowed after a Logcat warning, since
 * this must never crash the app or block the file destination running alongside it.
 */
class RemoteLogDestination(context: Context) : LogDestination {

    private val appContext = context.applicationContext
    private val runtimeUnit = RuntimeUnit(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One id per app process, not per login session — there is no app-wide login-session id today. */
    private val sessionId = UUID.randomUUID().toString()

    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    private val api: IRemoteLogApi by lazy {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(IRemoteLogApi::class.java)
    }

    override fun write(entry: LogEntry) {
        if (entry.level != LogLevel.ERROR) return

        scope.launch {
            try {
                api.sendLog(buildRequest(entry))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ship log entry to remote log endpoint", e)
            }
        }
    }

    private fun buildRequest(entry: LogEntry): RemoteLogRequest {
        val throwable = entry.throwable
        return RemoteLogRequest(
            deviceKey = deviceKey(),
            appName = APP_NAME,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            platform = PLATFORM,
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = Build.MODEL ?: "unknown",
            sessionId = sessionId,
            logId = UUID.randomUUID().toString(),
            severity = severityOf(entry.level),
            timestamp = timestampFormat.get()!!.format(Date(entry.timestampMillis)),
            message = PhiRedactor.redact(entry.message),
            tag = entry.className,
            event = LogEventClassifier.classify(entry).name,
            context = buildMap {
                put("file", entry.fileName)
                put("method", entry.methodName)
                entry.operatorName?.let { put("operator", it) }
            },
            error = throwable?.let {
                RemoteLogError(
                    type = it::class.java.name,
                    message = PhiRedactor.redact(entry.humanReadableError ?: (it.message ?: it.toString())),
                    stackTrace = PhiRedactor.redact(stackTraceOf(it)),
                    isFatal = false
                )
            },
            network = currentNetworkInfo()
        )
    }

    private fun deviceKey(): String? =
        try {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    private fun currentNetworkInfo(): RemoteLogNetwork = try {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivityManager.activeNetwork
            ?.let { connectivityManager.getNetworkCapabilities(it) }
        val type = when {
            capabilities == null -> "unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val isOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        RemoteLogNetwork(type = type, isOnline = isOnline)
    } catch (e: Exception) {
        RemoteLogNetwork(type = "unknown", isOnline = false)
    }

    private fun severityOf(level: LogLevel): Int = when (level) {
        LogLevel.ERROR -> 3
        LogLevel.WARN -> 4
        LogLevel.INFO -> 6
        LogLevel.DEBUG, LogLevel.VERBOSE -> 7
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    /**
     * Attaches `X-Server-Key`/`Content-Type` like the shared
     * [com.dispensesure.retail.core.api.interfaceDetail.HeaderInterceptor], but never logs
     * through `AppLogger` on failure — see the class doc for why.
     */
    private fun headerInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val serverKey = try {
            runtimeUnit.material()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve server key — remote log request sent without it", e)
            ""
        }
        val request = original.newBuilder()
            .addHeader("X-Server-Key", serverKey)
            .addHeader("Content-Type", URLConstant.CONTENT_TYPE)
            .build()
        chain.proceed(request)
    }

    companion object {
        private const val TAG = "RemoteLogDestination"
        private const val APP_NAME = "dispensesure"
        private const val PLATFORM = "android"
    }
}
