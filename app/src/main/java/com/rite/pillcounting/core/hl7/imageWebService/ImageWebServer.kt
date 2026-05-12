package com.rite.pillcounting.core.hl7.imageWebService

import ImageNanoServer
import android.content.Context
import android.util.Log
import com.rite.pillcounting.core.utils.logger.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ImageWebServer(private val context: Context) {

    companion object {
        private const val PORT = 8443
        private const val TAG = "ImageWebServer"
        private val logger = AppLogger(TlsImageKeystoreUtil.TAG)
    }

    private var server: ImageNanoServer? = null

    fun start() {
        if (server != null) return

        val keyStore = TlsImageKeystoreUtil.ensureKeystore(context)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, TlsImageKeystoreUtil.password(context))  // Pass context

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        server = ImageNanoServer(context, PORT, sslContext.serverSocketFactory)
        server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        logger.i("HTTPS Image Server started on port $PORT")
        // FIX 3: Don't log the cert fingerprint — it reveals server identity
        // logger.i("Cert fingerprint: ${TlsImageKeystoreUtil.fingerprint(context)}")
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTPS Image Server stopped")
    }
}