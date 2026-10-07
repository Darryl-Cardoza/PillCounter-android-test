package com.dispensesure.retail.core.utils.logger.destination

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.security.RuntimeUnit
import com.dispensesure.retail.core.utils.common.NetworkUtils
import com.dispensesure.retail.core.utils.constants.URLConstant
import com.dispensesure.retail.core.utils.logger.LogDestination
import com.dispensesure.retail.core.utils.logger.LogEntry
import com.dispensesure.retail.core.utils.logger.LogEventClassifier
import com.dispensesure.retail.core.utils.logger.LogFormatter
import com.dispensesure.retail.core.utils.logger.LogLevel
import com.dispensesure.retail.core.utils.logger.LoggerConfig
import com.dispensesure.retail.core.utils.logger.PhiRedactor
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogError
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogNetwork
import com.dispensesure.retail.core.utils.logger.destination.dto.RemoteLogRequest
import com.dispensesure.retail.core.utils.logger.destination.remote.IRemoteLogApi
import com.dispensesure.retail.core.utils.preference.PreferenceHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * ERROR-only feed that ships each entry to the app's `/mobile/logs` ingest endpoint (which
 * forwards it to Datadog), in the payload shape agreed with the backend team. Wired in by
 * [com.dispensesure.retail.core.utils.logger.AppLogger.init] as the app's only [LogDestination].
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
 * this must never crash the app.
 *
 * When the device has no connectivity, the built request is queued to [pendingLogFile] instead
 * of being attempted, and flushed once connectivity returns (via the registered
 * [ConnectivityManager.NetworkCallback], and opportunistically after every successful send).
 */
class RemoteLogDestination internal constructor(
    context: Context,
    private val apiOverride: IRemoteLogApi?,
    private val pendingLogFileOverride: File?,
    private val isNetworkAvailable: (Context) -> Boolean = NetworkUtils::isNetworkAvailable,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    accessTokenOverride: (() -> String?)? = null
) : LogDestination {

    constructor(context: Context) : this(context, apiOverride = null, pendingLogFileOverride = null)

    private val appContext = context.applicationContext

    /**
     * The app's shared [RuntimeUnit] (the Hilt singleton that MainActivity clears after its
     * security check). Set once Hilt is up via
     * [com.dispensesure.retail.core.utils.logger.AppLogger.setRuntimeUnit]; a private copy would
     * never be cleared and could never return the key. Until it is set the key is unavailable and
     * ERROR entries queue to file.
     */
    @Volatile
    var runtimeUnit: RuntimeUnit? = null
    @Volatile
    private var keyBlockedUntil = 0L
    // Lazy: AppLogger.init() builds this before the rest of the app is ready.
    private val preferenceHelper by lazy { PreferenceHelper(appContext) }
    // Set while reading the token: a decrypt failure there logs an ERROR, which must not re-enter write().
    private val readingToken = ThreadLocal<Boolean>()
    private val accessToken: () -> String? = accessTokenOverride ?: {
        readingToken.set(true)
        try { preferenceHelper.getAccessToken() } catch (e: Exception) { null } finally { readingToken.remove() }
    }
    private val flushMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = CopyOnWriteArrayList<Job>()
    private val pendingLogFile by lazy {
        pendingLogFileOverride
            ?: File(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Logs"), PENDING_LOG_FILE_NAME)
    }
    /** Holds the batch being flushed, so a process death mid-flush can't lose it (picked up next flush). */
    private val flushingFile by lazy { File(pendingLogFile.parentFile, pendingLogFile.name + ".flushing") }
    private val pendingLock = Any()

    /**
     * Refreshes an expired access token for log sends. Set by the app once Hilt is up
     * (see [com.dispensesure.retail.core.utils.logger.AppLogger.setTokenAuthenticator]).
     */
    @Volatile
    var tokenAuthenticator: Authenticator? = null
    @Volatile
    private var refreshBlockedUntil = 0L
    private val refreshLock = Any()

    /**
     * On 401: reuse a token another caller already refreshed, else delegate to [tokenAuthenticator].
     * A failed refresh blocks further attempts for [REFRESH_COOLDOWN_MS], because the refresh
     * failure is itself logged at ERROR and would otherwise loop send -> 401 -> refresh -> log.
     */
    private val logAuthenticator = Authenticator { route, response ->
        if (response.priorResponse != null) return@Authenticator null
        synchronized(refreshLock) {
            val used = response.request.header("Authorization")?.removePrefix("Bearer ")
            val current = accessToken()
            if (!current.isNullOrBlank() && current != used) {
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer $current").build()
            }
            if (System.currentTimeMillis() < refreshBlockedUntil) return@synchronized null
            val delegate = tokenAuthenticator ?: return@synchronized null
            val refreshed = try {
                delegate.authenticate(route, response)
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh for log send failed", e)
                null
            }
            refreshed.also {
                if (it == null) refreshBlockedUntil = System.currentTimeMillis() + REFRESH_COOLDOWN_MS
            }
        }
    }

    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter by lazy { moshi.adapter(RemoteLogRequest::class.java) }

    private val api: IRemoteLogApi by lazy {
        apiOverride ?: run {
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(headerInterceptor())
                .authenticator(logAuthenticator)
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
    }

    init {
        registerConnectivityCallback()
    }

    override fun write(entry: LogEntry) {
        if (entry.level != LogLevel.ERROR || readingToken.get() == true) return

        // Everything heavier than capturing the session id runs off the caller's thread (often main).
        val sessionId = LoggerConfig.sessionId
        launchTracked {
            val request = buildRequest(entry, sessionId)
            // /mobile/logs needs the user's bearer token, so until login (or while offline) queue
            // to file; the backlog is flushed by connectivity, the next send, or onUserLoggedIn().
            if (!isNetworkAvailable(appContext) || accessToken().isNullOrBlank()) {
                persistNow(request)
                return@launchTracked
            }
            when (sendWithRetry(request)) {
                SendResult.OK -> flushPendingNow()
                SendResult.DROP -> Log.w(TAG, "Log entry permanently rejected by the server — dropped")
                else -> {
                    Log.w(TAG, "Log entry not delivered after $MAX_ATTEMPTS attempts — queuing for next connectivity")
                    persistNow(request)
                }
            }
        }
    }

    private enum class SendResult {
        OK,
        /** Permanent rejection (e.g. 400/413/422): retrying can never succeed, so drop it. */
        DROP,
        /** Network exception or 401 (token couldn't be refreshed): not the entry's fault, doesn't use up its attempts. */
        RETRY_FREE,
        /** Server answered with another failure (5xx, 408, 429, 403...): counts toward [MAX_QUEUE_ATTEMPTS]. */
        RETRY_COUNTED
    }

    /** One POST. A 401 is first handled by [logAuthenticator] (token refresh + replay). */
    private suspend fun trySend(request: RemoteLogRequest): SendResult =
        try {
            val response = api.sendLog(request)
            val code = response.code()
            when {
                response.isSuccessful -> SendResult.OK
                code == 401 -> SendResult.RETRY_FREE
                code in 400..499 && code != 403 && code != 408 && code != 429 -> SendResult.DROP
                else -> SendResult.RETRY_COUNTED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.RETRY_FREE
        }

    /** Up to [MAX_ATTEMPTS] tries with linear backoff ([retryDelayMs] * attempt); stops early on OK/DROP. */
    private suspend fun sendWithRetry(request: RemoteLogRequest): SendResult {
        var result = SendResult.RETRY_FREE
        for (attempt in 1..MAX_ATTEMPTS) {
            result = trySend(request)
            if (result == SendResult.OK || result == SendResult.DROP) return result
            if (attempt < MAX_ATTEMPTS) delay(retryDelayMs * attempt)
        }
        return result
    }

    private fun launchTracked(block: suspend () -> Unit) {
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Uncaught here would reach the thread's handler and kill the app.
                Log.w(TAG, "Remote log task failed", e)
            }
        }
        activeJobs += job
        job.invokeOnCompletion { activeJobs -= job }
    }

    /** Test-only: blocks until every in-flight send/persist/flush launched so far has finished. */
    internal fun awaitIdleForTest() {
        while (activeJobs.isNotEmpty()) {
            runBlocking { activeJobs.toList().forEach { it.join() } }
        }
    }

    private fun registerConnectivityCallback() {
        try {
            val connectivityManager =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = flushPending()
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register connectivity callback for queued log delivery", e)
        }
    }

    /** Queue lines are `<attempts>\t<json>`; an un-prefixed line (older format) means 0 attempts. */
    private fun persistNow(request: RemoteLogRequest, attempts: Int = 0) {
        try {
            val json = requestAdapter.toJson(request)
            synchronized(pendingLock) {
                pendingLogFile.parentFile?.mkdirs()
                pendingLogFile.appendText("$attempts\t$json" + System.lineSeparator())
                trimPendingFile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue log entry for later delivery", e)
        }
    }

    /** Caps the queue at [MAX_PENDING_BYTES] by dropping the oldest lines down to half that. Caller holds [pendingLock]. */
    private fun trimPendingFile() {
        if (pendingLogFile.length() <= MAX_PENDING_BYTES) return
        var total = 0L
        val newest = pendingLogFile.readLines().asReversed()
            .takeWhile { total += it.length + 1; total <= MAX_PENDING_BYTES / 2 }.asReversed()
        pendingLogFile.writeText(newest.joinToString("") { it + System.lineSeparator() })
    }

    /** Ships anything queued while logged out/offline. Also called right after login succeeds. */
    fun flushPending() {
        launchTracked { flushPendingNow() }
    }

    /**
     * Best-effort flush before logout clears the token, so queued entries go out under the
     * logging-out user's own credentials. On timeout or failure the entries simply stay queued
     * in the file (see [flushPendingNow]) and ship after the next login.
     */
    suspend fun flushBeforeLogout(timeoutMs: Long = LOGOUT_FLUSH_TIMEOUT_MS) {
        // File I/O and the network call stay off the caller's (Main) thread.
        try {
            withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { flushPendingNow(wait = true) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: a storage failure must not block logout. Log.w, not logger, to avoid a log loop.
            Log.w(TAG, "Flush before logout failed — entries stay queued", e)
        }
    }

    /**
     * Reads and empties the queue, sends each line once, and writes back whatever is left.
     * Cancellation-safe: in `finally` the unsent remainder (including a line interrupted
     * mid-send) is always re-appended, so a timeout can't lose entries.
     */
    private suspend fun flushPendingNow(wait: Boolean = false) {
        if (!isNetworkAvailable(appContext) || accessToken().isNullOrBlank()) return
        // One flush at a time: a second would re-send the same .flushing batch and duplicate the unsent lines.
        if (wait) flushMutex.lock() else if (!flushMutex.tryLock()) return
        try {
            flushLocked()
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun flushLocked() {
        // Copy the queue into flushingFile before emptying it, so a process death mid-flush
        // leaves the batch on disk (a leftover flushingFile is merged into the next flush).
        val queued = synchronized(pendingLock) {
            if (!flushingFile.exists() && !pendingLogFile.exists()) return
            val lines = (if (flushingFile.exists()) flushingFile.readLines() else emptyList()) +
                (if (pendingLogFile.exists()) pendingLogFile.readLines() else emptyList())
            flushingFile.writeText(lines.joinToString("") { it + System.lineSeparator() })
            pendingLogFile.writeText("")
            lines
        }
        val keep = mutableListOf<String>()
        var index = 0
        try {
            while (index < queued.size) {
                val (kept, stop) = processQueuedLine(queued[index])
                // Network down or token unusable: the rest would fail the same way, so leave them queued.
                if (stop) break
                kept?.let { keep += it }
                index++
            }
        } finally {
            keep += queued.drop(index)
            synchronized(pendingLock) {
                if (keep.isNotEmpty()) {
                    pendingLogFile.appendText(keep.joinToString(separator = "") { it + System.lineSeparator() })
                }
                flushingFile.delete()
            }
        }
    }

    /**
     * Sends one queued line. Returns the line to keep (with updated attempts, or null when done with it)
     * and whether the flush should stop here; on stop the line itself is kept unchanged by the caller.
     */
    private suspend fun processQueuedLine(line: String): Pair<String?, Boolean> {
        if (line.isBlank()) return null to false
        val (attempts, json) = parseQueued(line)
        return try {
            val request = requestAdapter.fromJson(json) ?: return null to false
            when (trySend(request)) {
                SendResult.OK, SendResult.DROP -> null to false
                SendResult.RETRY_FREE -> line to true
                SendResult.RETRY_COUNTED ->
                    if (attempts + 1 >= MAX_QUEUE_ATTEMPTS) {
                        Log.w(TAG, "Queued log entry rejected $MAX_QUEUE_ATTEMPTS times — dropped")
                        null to false
                    } else "${attempts + 1}\t$json" to false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            line to false // unparseable or unexpected: keep for the next flush
        }
    }

    private fun parseQueued(line: String): Pair<Int, String> {
        val tab = line.indexOf('\t')
        val attempts = if (tab > 0) line.substring(0, tab).toIntOrNull() else null
        return if (attempts != null) attempts to line.substring(tab + 1) else 0 to line
    }

    private fun buildRequest(entry: LogEntry, sessionId: String): RemoteLogRequest {
        val throwable = entry.throwable
        return RemoteLogRequest(
            deviceKey = deviceKey(),
            appName = APP_NAME,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            buildNumber = BuildConfig.BUILD_VERSION_ID,
            platform = PLATFORM,
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceModel = Build.MODEL ?: "unknown",
            sessionId = sessionId,
            logId = UUID.randomUUID().toString(),
            severity = severityOf(entry.level),
            timestamp = timestampFormat.get()!!.format(Date(entry.timestampMillis)),
            // "File -> Class -> Method -> message", see LogFormatter.formatSingleLine.
            message = PhiRedactor.redact(LogFormatter.formatSingleLine(entry)),
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
        LogLevel.WARN -> 2
        LogLevel.INFO -> 1
        LogLevel.DEBUG, LogLevel.VERBOSE -> 0
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    /**
     * The key, or an [IOException] so the send fails and the entry is queued instead of going out
     * keyless. A failure blocks retrieval for [KEY_COOLDOWN_MS] so a broken keystore isn't hit on every send.
     */
    private fun serverKey(): String {
        val now = System.currentTimeMillis()
        if (now < keyBlockedUntil) throw IOException("Server key unavailable (cooling down)")
        val unit = runtimeUnit ?: throw IOException("Server key unavailable: RuntimeUnit not attached yet")
        return try {
            unit.material()
        } catch (e: Exception) {
            keyBlockedUntil = now + KEY_COOLDOWN_MS
            Log.w(TAG, "Failed to retrieve server key — remote log request not sent", e)
            throw IOException("Server key unavailable", e)
        }
    }

    private fun headerInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val serverKey = serverKey()
        val builder = original.newBuilder()
            .addHeader("X-Server-Key", serverKey)
            .addHeader("Content-Type", URLConstant.CONTENT_TYPE)
        accessToken()?.takeIf { it.isNotBlank() }?.let { builder.addHeader("Authorization", "Bearer $it") }
        val request = builder.build()
        chain.proceed(request)
    }

    companion object {
        private const val TAG = "RemoteLogDestination"
        private const val APP_NAME = "dispensesure"
        private const val PLATFORM = "android"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_QUEUE_ATTEMPTS = 5
        private const val LOGOUT_FLUSH_TIMEOUT_MS = 2_000L
        private const val REFRESH_COOLDOWN_MS = 60_000L
        private const val KEY_COOLDOWN_MS = 30_000L
        private const val MAX_PENDING_BYTES = 1_000_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val PENDING_LOG_FILE_NAME = "dispensesure_logs.txt"
    }
}
