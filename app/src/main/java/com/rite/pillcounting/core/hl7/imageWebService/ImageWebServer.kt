package com.rite.pillcounting.core.hl7.imageWebService

import android.content.Context
import com.rite.pillcounting.core.hl7.mllp.tls.TlsImageKeystoreUtil
import com.rite.pillcounting.core.utils.logger.AppLogger
import com.rite.pillcounting.core.utils.preference.PreferenceHelper
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ImageWebServer(private val context: Context) {

    companion object {
        const val PORT = 8080
    }

    private val logger = AppLogger("ImageWebServer")
    private var server: ImageNanoServer? = null

    fun start() {
        if (server != null) return

        val bypassTls = PreferenceHelper(context).isBypassTlsEnabled()

        if (bypassTls) {
            server = ImageNanoServer(context, PORT, sslFactory = null)
            server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            logger.i("HTTP Image Server started on port $PORT (TLS bypassed)")
            return
        }

        val keyStore = TlsImageKeystoreUtil.ensureKeystore(context)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, TlsImageKeystoreUtil.password(context))  // Pass context

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        server = ImageNanoServer(context, PORT, sslContext.serverSocketFactory)
        server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        logger.i("HTTPS Image Server started on port $PORT")
        logger.i("Cert fingerprint: ${TlsImageKeystoreUtil.fingerprint(context)}")
    }

    fun stop() {
        server?.stop()
        server = null
        logger.i("HTTPS Image Server stopped")
    }
}
