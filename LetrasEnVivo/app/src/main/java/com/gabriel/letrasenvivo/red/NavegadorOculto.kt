package com.gabriel.letrasenvivo.red

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gabriel.letrasenvivo.Registro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * WebView sin ventana que carga una página como lo haría el navegador. Sirve para pasar el
 * desafío automático de Cloudflare ("Just a moment..."): al resolverse, la cookie queda en el
 * CookieManager y las siguientes peticiones normales del cliente HTTP ya pasan.
 */
class NavegadorOculto(private val context: Context) {

    private val hiloPrincipal = Handler(Looper.getMainLooper())

    /** Devuelve el HTML final de la página, o null si no se pudo (sin WebView, tiempo agotado, desafío no superado). */
    suspend fun obtener(url: String, tiempoMaximoMs: Long = 30_000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(tiempoMaximoMs) {
            suspendCancellableCoroutine { continuacion ->
                var vista: WebView? = null
                var terminado = false
                fun terminar(resultado: String?) {
                    if (terminado) return
                    terminado = true
                    vista?.let { v -> hiloPrincipal.post { try { v.stopLoading(); v.destroy() } catch (_: Exception) {} } }
                    vista = null
                    if (continuacion.isActive) continuacion.resume(resultado)
                }
                try {
                    val v = crear()
                    vista = v
                    continuacion.invokeOnCancellation { terminar(null) }
                    v.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            // Ahorra datos: no hace falta cargar imágenes ni fuentes para leer el HTML.
                            val ruta = request.url.path.orEmpty()
                            return if (ruta.endsWith(".png") || ruta.endsWith(".jpg") || ruta.endsWith(".webp") ||
                                ruta.endsWith(".woff") || ruta.endsWith(".woff2") || ruta.endsWith(".svg") || ruta.endsWith(".gif")) {
                                WebResourceResponse("text/plain", "utf-8", null)
                            } else null
                        }

                        override fun onPageFinished(view: WebView, urlCargada: String?) {
                            if (terminado) return
                            view.evaluateJavascript("document.title") { tituloJson ->
                                val titulo = decodificar(tituloJson)
                                if (titulo.contains("Just a moment", ignoreCase = true) || titulo.contains("Attention Required", ignoreCase = true)) {
                                    Registro.info(Registro.RED, "WebView: desafío de Cloudflare en curso para $url")
                                    return@evaluateJavascript // Cloudflare recargará la página sola.
                                }
                                view.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                                    val html = decodificar(htmlJson)
                                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                                    Registro.info(Registro.RED, "WebView: página obtenida (${html.length} car.) $url")
                                    terminar(html.ifEmpty { null })
                                }
                            }
                        }
                    }
                    v.loadUrl(url)
                } catch (e: Exception) {
                    Registro.error(Registro.RED, "WebView no disponible", e)
                    terminar(null)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun crear(): WebView {
        val v = WebView(context.applicationContext)
        v.settings.javaScriptEnabled = true
        v.settings.domStorageEnabled = true
        v.settings.loadsImagesAutomatically = false
        v.settings.blockNetworkImage = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        return v
    }

    private fun decodificar(json: String?): String {
        if (json.isNullOrEmpty() || json == "null") return ""
        return try { JSONTokener(json).nextValue() as? String ?: "" } catch (e: Exception) { "" }
    }
}
