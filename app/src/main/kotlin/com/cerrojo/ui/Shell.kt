package com.cerrojo.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cerrojo.datos.Almacen
import com.cerrojo.datos.Sesion
import java.net.URLEncoder

class Shell : ComponentActivity() {
    private var web: WebView? = null

    /**
     * Vive fuera de la composicion porque el gesto de atras tambien lo
     * consulta, y ese callback no es un Composable.
     */
    private var sinConexion by mutableStateOf(false)

    /**
     * La web ha mandado a su propia pantalla de entrada. Pasa si es la primera
     * vez sin haber entrado en Seal, o si el navegador perdio sus cookies.
     * En vez de dejar que escriba la contraseña ahi —con lo que el lado nativo
     * seguiria sin cuenta y volveria a pedirsela— se ofrece entrar aqui una
     * vez, que sirve para las dos.
     */
    private var webPideEntrar by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = Almacen(this).urlWeb
        val primeraCarga = urlDeEntrada(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                // Con el aviso de sin conexion delante, retroceder movería un
                // WebView que el usuario no ve: parecería que atras no hace
                // nada. Ahi se sale, que es lo unico con efecto visible.
                if (!sinConexion && !webPideEntrar && w != null && w.canGoBack()) w.goBack() else finish()
            }
        })

        setContent {
            TemaDeSeal {
                // El WebView se queda montado SIEMPRE y el aviso de sin
                // conexion se pinta encima. Si se desmontara, reintentar
                // significaria crear otro desde cero: se perderia la posicion,
                // lo que el usuario estuviera escribiendo, y la pagina donde
                // fallo — volveria a la portada.
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                // Que la web sepa que esta dentro de Seal.
                                // El WebView de Android no tiene Push API,
                                // asi que ahi el boton de activar
                                // notificaciones no puede funcionar NUNCA y
                                // la web decia 'este navegador no puede
                                // avisarte': cierto, pero inutil, porque
                                // aqui los avisos los da Seal.
                                settings.userAgentString =
                                    settings.userAgentString + " Seal/1.9"
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                // Sin un WebChromeClient el WebView no muestra alert/confirm de la web
                                // y los da por cancelados: el boton de borrar tareas no hacia nada.
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                                        CookieManager.getInstance().flush()
                                        // El proxy de la web manda a /login
                                        // cuando no hay sesion. Es la unica
                                        // señal fiable de que la entrega no
                                        // llego o ya no vale.
                                        if (u != null && u.contains("/login")) webPideEntrar = true
                                    }
                                    override fun onReceivedError(
                                        v: WebView, req: WebResourceRequest, err: WebResourceError,
                                    ) {
                                        // Solo el documento principal: una
                                        // imagen que no carga no es estar sin
                                        // conexion.
                                        if (req.isForMainFrame) sinConexion = true
                                    }
                                }
                                // El WebView pinta blanco hasta que la pagina
                                // se dibuja. Sobre un tema negro eso es un
                                // fogonazo en cada apertura.
                                setBackgroundColor(0xFF060404.toInt())
                                web = this
                                loadUrl(primeraCarga)
                            }
                        },
                        // Sin esto el WebView se queda con sus recursos nativos
                        // sin soltar al cerrar la pantalla.
                        onRelease = {
                            it.destroy()
                            web = null
                        },
                    )

                    if (sinConexion) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                // Pintar un fondo no se come los toques: sin
                                // esto, tocar fuera del boton llegaria al
                                // WebView de debajo y se podria navegar o
                                // enviar algo en una pagina que no se ve.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                        }
                                    }
                                }
                                .padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Sin conexión", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text("El cerrojo sigue funcionando igual. Esto es solo la parte que necesita internet.")
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = {
                                sinConexion = false
                                // Recargar el mismo WebView reintenta la pagina
                                // que fallo, no la portada.
                                web?.reload()
                            }) { Text("Reintentar") }
                        }
                    }

                    if (webPideEntrar) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                // Igual que el aviso de sin conexion: pintar un
                                // fondo no reclama los toques, y sin esto se
                                // podria escribir en el formulario de la web
                                // que hay justo debajo sin verlo.
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent().changes.forEach { it.consume() }
                                        }
                                    }
                                },
                        ) {
                            PantallaDeEntrada(
                                titulo = "Entra una vez",
                                explicacion = "La web te pide iniciar sesión. Hazlo aquí y vale para las dos: la app y la web de dentro.",
                                alEntrar = {
                                    webPideEntrar = false
                                    // Se recarga por la ruta de entrega, que es
                                    // la que planta la sesion en el navegador.
                                    web?.loadUrl(urlDeEntrada(Almacen(this@Shell).urlWeb))
                                },
                                alSaltar = { webPideEntrar = false },
                            )
                        }
                    }

                    // Ajustes de Seal (apps vigiladas, cuenta, permisos de
                    // MIUI) — no de la web. Desde que el icono del lanzador
                    // lleva directo aqui, sin este boton esa pantalla se
                    // quedaria sin ninguna entrada obvia. Fijo y pequeño, por
                    // encima incluso del aviso de "sin conexión": los ajustes
                    // de Seal no dependen de la red.
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape)
                            .clickable {
                                startActivity(
                                    Intent(this@Shell, Principal::class.java)
                                        .putExtra(Principal.EXTRA_AJUSTES, true)
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    /**
     * Si Seal acaba de iniciar sesion, la web se abre por una ruta que recibe
     * los tokens y los planta como sesion del navegador. Asi solo se entra una
     * vez: en Seal.
     *
     * Van en el fragmento de la URL (detras de #) a proposito. El fragmento no
     * viaja al servidor, asi que los tokens no acaban en los registros de
     * Vercel; es el mismo sitio donde los pone Supabase en sus propios enlaces
     * de acceso.
     */
    private fun urlDeEntrada(url: String): String {
        val entrega = Sesion(this).tomarEntregaParaWeb() ?: return url
        fun cod(v: String) = URLEncoder.encode(v, "UTF-8")
        return url.trimEnd('/') + "/auth/sesion-movil#access_token=" +
            cod(entrega.first) + "&refresh_token=" + cod(entrega.second)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
