import android.content.Context
import com.rite.pillcounting.core.hl7.imageWebService.TlsImageKeystoreUtil
import com.rite.pillcounting.core.utils.logger.AppLogger
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class ImageWebServer(private val context: Context) {

    companion object {
        private const val PORT = 8443
    }

    private val logger = AppLogger("ImageWebServer")
    private var server: ImageNanoServer? = null

    fun start() {
        if (server != null) return

        // Build SSLServerSocketFactory from PKCS12 keystore
        val keyStore = TlsImageKeystoreUtil.ensureKeystore(context)

        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(keyStore, TlsImageKeystoreUtil.password())

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        server = ImageNanoServer(
            context = context,
            port = PORT,
            sslFactory = sslContext.serverSocketFactory
        )

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