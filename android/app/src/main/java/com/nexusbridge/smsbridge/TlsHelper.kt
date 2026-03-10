package com.nexusbridge.smsbridge

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds an OkHttpClient using the system CA trust store by default.
 *
 * If cert.der is a self-signed certificate (not issued by a public CA), it is
 * added to the trust store in addition to the system CAs so pinned connections
 * still work.  If the server uses a normal CA-signed certificate the bundled
 * cert.der is ignored and standard validation applies — this allows the app to
 * work on both Wi-Fi and mobile networks without issues.
 */
object TlsHelper {

    private const val TAG = "TlsHelper"

    fun buildClient(context: Context): OkHttpClient {
        val trustManager = buildTrustManager(context)
        val builder = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        if (trustManager != null) {
            // cert.der is self-signed — use augmented trust store
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            Log.d(TAG, "Using augmented trust store (cert.der is self-signed)")
        } else {
            // cert.der is CA-signed — system trust store is sufficient
            Log.d(TAG, "Using system trust store")
        }

        return builder.build()
    }

    /**
     * Returns a custom [X509TrustManager] that merges the system CAs with
     * cert.der — but only when cert.der is self-signed.  Returns null when
     * cert.der is already trusted by the system (i.e. CA-signed), so the
     * default OkHttp behaviour is used instead.
     */
    private fun buildTrustManager(context: Context): X509TrustManager? {
        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val certStream: InputStream = context.resources.openRawResource(R.raw.cert)
            val certificate = certFactory.generateCertificate(certStream) as X509Certificate
            certStream.close()

            // A self-signed cert has the same issuer and subject
            val isSelfSigned = certificate.issuerX500Principal == certificate.subjectX500Principal
            if (!isSelfSigned) {
                // CA-signed: system trust store handles this fine on any network
                return null
            }

            // Self-signed: build a trust store that includes both system CAs and our cert
            val systemTmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply { init(null as KeyStore?) }   // null = load system default

            val combinedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                // Copy system trusted certs
                val systemKs = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
                systemKs.aliases().asSequence().forEach { alias ->
                    getCertificate(alias)?.let { setCertificateEntry(alias, it) }
                    systemKs.getCertificate(alias)?.let { setCertificateEntry(alias, it) }
                }
                // Add our self-signed cert
                setCertificateEntry("nexusbridge-pinned", certificate)
            }

            val tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            ).apply { init(combinedKeyStore) }

            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cert.der, falling back to system trust store: ${e.message}")
            null
        }
    }
}
