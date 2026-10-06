package com.cerrojo.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cerrojo.core.Estado
import com.cerrojo.core.Evento
import com.cerrojo.core.avanzar
import com.cerrojo.core.limitesDe
import com.cerrojo.datos.Almacen
import kotlinx.coroutines.delay

private const val SEGUNDOS_DE_FRICCION = 45

class PantallaDeBloqueo : ComponentActivity() {
    /** Compose lo lee para parar la cuenta atras cuando la pantalla deja de verse. */
    private var enPantalla by mutableStateOf(true)

    override fun onStart() {
        super.onStart()
        enPantalla = true
    }

    override fun onStop() {
        super.onStop()
        enPantalla = false
    }

    /**
     * `singleTask` reutiliza la instancia viva. Sin esto, el bloqueo de OTRA
     * app reaprovecharia esta pantalla mostrando el nombre y el mensaje de la
     * anterior, y el boton de desbloquear liberaria la que no era.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Recrear solo si el bloqueo es de OTRA app. El servicio reintenta el
        // suyo mientras no vea esta pantalla delante, y en esos primeros
        // segundos un recreate() reiniciaria una cuenta atras ya empezada.
        val otraApp = intent.getStringExtra("paquete") != getIntent()?.getStringExtra("paquete")
        setIntent(intent)
        if (otraApp) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paquete = intent.getStringExtra("paquete") ?: return finish()
        val almacen = Almacen(this)
        val nombre = nombreDeApp(paquete)
        // Castigo a 0 de Voluntad: otro motivo, otro texto, y sin salida de
        // friccion — se sale haciendo tareas, no esperando 45 segundos.
        val castigo = intent.getBooleanExtra("castigo", false)
        val detalle = if (castigo)
            "Tu Voluntad ha llegado a 0. Todas tus apps vigiladas están bloqueadas hasta que vuelva a " +
                "${almacen.castigoHasta}. Se sube haciendo tareas."
        else detalleDelBloqueo(almacen, paquete)

        setContent {
            TemaDeSeal {
                var restantes by remember { mutableIntStateOf(-1) }

                // Irse no congela la espera: la cancela (restantes = -1, mas
                // abajo). La friccion son 45 segundos MIRANDO esta pantalla,
                // no 45 de reloj mientras haces otra cosa: si la cuenta
                // siguiera de fondo, o si volver la reanudase donde se quedo,
                // bastaria con pulsar, salir y volver para saltarse casi todo
                // el tiempo. Volver la empieza de cero: mas estricto que lo
                // que pide la spec, pero cumple de sobra los 45 s con la
                // pantalla encendida.
                LaunchedEffect(restantes, enPantalla) {
                    if (!enPantalla) {
                        if (restantes >= 0) restantes = -1
                        return@LaunchedEffect
                    }
                    if (restantes > 0) { delay(1000); restantes -= 1 }
                    else if (restantes == 0) {
                        val limites = almacen.limites(paquete) ?: limitesDe(30, 1)
                        almacen.guardarEstado(
                            paquete,
                            avanzar(almacen.estado(paquete), Evento.Desbloqueo, limites)
                        )
                        finish()
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(nombre, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        // La barra, llena: lo que ha pasado se ve antes de leerlo.
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (castigo) "Castigo." else "Barra llena.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            detalle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(40.dp))
                        Button(onClick = { irAlInicio() }, Modifier.fillMaxWidth()) { Text("Salir") }
                        Spacer(Modifier.height(16.dp))
                        if (castigo) {
                            // Sin "desbloquear igualmente" durante el castigo.
                        } else if (restantes < 0) {
                            TextButton(onClick = { restantes = SEGUNDOS_DE_FRICCION }) {
                                Text("Desbloquear igualmente")
                            }
                        } else {
                            Text("Desbloqueando en $restantes s — no cierres esta pantalla")
                        }
                    }
                }
            }
        }
    }

    /**
     * Por que se bloquea y hasta cuando. Antes solo decia "Toca descansar.
     * Vuelve luego.", y con eso el usuario no podia saber si el bloqueo era
     * el que tocaba: se quejo de que le bloqueaba antes de tiempo y no habia
     * manera de comprobarlo desde el movil.
     */
    private fun detalleDelBloqueo(almacen: Almacen, paquete: String): String {
        val limites = almacen.limites(paquete) ?: limitesDe(30, 1)
        val e = almacen.estado(paquete)
        val vuelta = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.finBloqueoMs))
        return "Has cargado ${enHoras(limites.barraMin)} de uso. Bloqueada ${enHoras(limites.bloqueoMin)}: " +
            "vuelve a abrirse a las $vuelta, con la barra vacía. Hoy llevas ${enHoras(e.segHoy / 60)}."
    }

    private fun nombreDeApp(paquete: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquete, 0)).toString()
    } catch (_: Exception) { paquete }

    private fun irAlInicio() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onBackPressed() = irAlInicio()

    companion object {
        fun mostrar(context: Context, paquete: String, estado: Estado, castigo: Boolean = false) {
            context.startActivity(
                Intent(context, PantallaDeBloqueo::class.java)
                    .putExtra("paquete", paquete)
                    .putExtra("estado", estado.name)
                    .putExtra("castigo", castigo)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
