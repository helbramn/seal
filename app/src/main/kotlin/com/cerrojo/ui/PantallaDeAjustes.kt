package com.cerrojo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cerrojo.core.SUELO_POR_DEFECTO_MIN
import com.cerrojo.core.carga
import com.cerrojo.core.rangoDeBloqueo
import com.cerrojo.core.mediaDeUso
import com.cerrojo.datos.Almacen
import com.cerrojo.sistema.LectorDeUso

private data class AppInstalada(
    val paquete: String,
    val nombre: String,
    /**
     * Se puede vigilar. Antes era "no es del sistema" (FLAG_SYSTEM), y eso
     * dejaba fuera YouTube, Chrome o Gmail: en los Xiaomi vienen de fabrica y
     * siguen marcadas como del sistema aunque se actualicen desde Play Store.
     * Ahora solo se excluye lo que seria peligroso bloquear (ver noBloqueables).
     */
    val elegible: Boolean,
    /**
     * Tu dia tipico con esta app. Sale de mediaDeUso() del core, la MISMA
     * funcion con la que el motor calcula el objetivo: antes esta pantalla
     * tenia su propia mediana, y dos caminos distintos leyendo el uso pueden
     * enseñar un numero y limitar por otro.
     */
    val minutosDia: Int,
    /** Lo que llevas hoy, que es lo que todo el mundo cree que dice el otro. */
    val minutosHoy: Int,
)

/** "2 h 30 min" se entiende; "150 min" hay que traducirlo mentalmente. */
internal fun enHoras(minutos: Int): String = when {
    minutos < 60 -> "$minutos min"
    minutos % 60 == 0 -> "${minutos / 60} h"
    else -> "${minutos / 60} h ${minutos % 60} min"
}

@Composable
private fun Rotulo(texto: String) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/**
 * Bloque que se abre y se cierra. Lo que hay dentro no es urgente, pero tiene
 * que poder verse: esconderlo del todo es como no tenerlo.
 */
@Composable
private fun Desplegable(
    titulo: String,
    abiertoAlPrincipio: Boolean = false,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    var abierto by remember { mutableStateOf(abiertoAlPrincipio) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { abierto = !abierto }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(titulo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (abierto) "−" else "+",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(abierto) {
            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = contenido,
            )
        }
    }
}

@Composable
fun PantallaDeAjustes() {
    val context = LocalContext.current
    val almacen = remember { Almacen(context) }
    val lector = remember { LectorDeUso(context) }
    val alcance = rememberCoroutineScope()
    var vigiladas by remember { mutableStateOf(almacen.appsVigiladas()) }
    var refresco by remember { mutableIntStateOf(0) }
    var verTodas by remember { mutableStateOf(false) }

    // La barra de cada app, en vivo: la carga el servicio cada segundo y aqui
    // se relee cada dos para verla subir.
    var cargas by remember { mutableStateOf(emptyMap<String, Pair<Float, String>>()) }
    LaunchedEffect(vigiladas) {
        while (true) {
            cargas = vigiladas.mapNotNull { p ->
                almacen.limites(p)?.let { l ->
                    val e = almacen.estado(p)
                    val texto = if (e.estado == com.cerrojo.core.Estado.ENFRIANDO)
                        "Bloqueada hasta las " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                            .format(java.util.Date(e.finBloqueoMs))
                    else "${e.segBarra / 60} de ${l.barraMin} min"
                    p to (e.carga(l) to texto)
                }
            }.toMap()
            delay(2_000)
        }
    }

    // Listar las apps instaladas resuelve un intent y un nombre por cada una, y
    // ademas hay que leer cuanto se usa cada una: en el hilo principal eso
    // congela el primer fotograma de la pantalla.
    var instaladas by remember { mutableStateOf<List<AppInstalada>?>(null) }
    LaunchedEffect(Unit) {
        instaladas = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val abribles = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            // Bloquear cualquiera de estas dejaria el movil inservible o sin
            // salida: Seal mismo, el escritorio, Ajustes (desde donde se le
            // quitan los permisos) y el telefono (llamadas de emergencia).
            val noBloqueables = setOfNotNull(
                context.packageName,
                "com.android.settings",
                pm.resolveActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
                )?.activityInfo?.packageName,
                context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage,
            )

            // Una sola consulta para todas, no una por app: preguntar el uso app
            // por app son cientos de llamadas al sistema y la pantalla tarda
            // segundos en aparecer.
            val porPaquete = mutableMapOf<String, MutableList<Int>>()
            val hoyPorPaquete = mutableMapOf<String, Int>()
            val queridos = abribles.map { it.packageName }.toSet()
            val hoy = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid")).toString()
            for (fila in lector.usoPorAppYDia(14) { it in queridos }) {
                porPaquete.getOrPut(fila.paquete) { mutableListOf() }.add(fila.minutos)
                if (fila.fecha == hoy) hoyPorPaquete[fila.paquete] = fila.minutos
            }

            abribles.map {
                AppInstalada(
                    it.packageName,
                    pm.getApplicationLabel(it).toString(),
                    it.packageName !in noBloqueables,
                    // mediaDeUso devuelve 30 min por defecto con menos de tres
                    // dias de historial, y ese es exactamente el numero con el
                    // que se calcularia el limite. Enseñar otro seria mentir.
                    porPaquete[it.packageName]?.let { d -> mediaDeUso(d) } ?: 0,
                    hoyPorPaquete[it.packageName] ?: 0,
                )
            }
        }
    }

    val lista = instaladas
    // Lo que mas usas, arriba. Alfabetico obliga a buscar; por uso, las
    // candidatas de verdad son las tres primeras.
    val ordenadas = remember(lista, vigiladas, verTodas) {
        lista.orEmpty()
            .filter { it.elegible || it.paquete in vigiladas }
            .sortedWith(compareByDescending<AppInstalada> { it.paquete in vigiladas }
                .thenByDescending { it.minutosDia }
                .thenBy { it.nombre.lowercase() })
            .let { todas ->
                if (verTodas) todas
                else todas.filter { it.paquete in vigiladas || it.minutosDia > 0 }.take(12)
            }
    }
    val hayMas = (lista?.size ?: 0) > ordenadas.size

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
    ) {
        item {
            Text("Seal", style = MaterialTheme.typography.displayLarge)
            Text(
                "Cada app vigilada tiene una barra que se llena mientras la usas. Cuando se llena, se bloquea un rato.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Lo primero que ve alguien que abre esto por primera vez. Antes no
        // habia NADA que explicara que hace la app ni que significan sus
        // numeros: salia una lista de apps y tres cifras sueltas.
        item {
            Desplegable("Cómo funciona", abiertoAlPrincipio = vigiladas.isEmpty()) {
                Paso("1", "Mide", "Seal mira cuánto has usado cada app en los últimos 14 días. Ese es tu punto de partida: no se inventa un tope, usa el tuyo.")
                Paso("2", "Carga", "Cada minuto con la app delante llena su barra. Cuando sales, la barra se queda donde estaba: no baja. Tú eliges de cuántos minutos es.")
                Paso("3", "Corta", "Barra llena = la app se bloquea el tiempo que elijas, dentro de un rango que sale de tu media: de un tercio de lo que la usas al día a la media entera (baja un 10 % cada lunes, nunca de ${SUELO_POR_DEFECTO_MIN} min). Al acabar, la barra vuelve a cero. También se vacía a las 5:00, cuando empieza el día (a la misma hora que en la web).")
                Paso("4", "Fricción", "La pantalla de bloqueo tiene una salida, pero cuesta: 45 segundos mirándola. Está para que abrirla sin pensar deje de ser gratis.")
                Text(
                    "Si Seal se queda sin permisos, o el móvil mata el servicio, lo dirás en «Comprobaciones», abajo. No se calla nunca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Rotulo("Qué apps te limita")
            Text(
                if (vigiladas.isEmpty())
                    "Ninguna todavía. Abajo están tus apps ordenadas por lo que las usas de verdad — empieza por las de arriba."
                else
                    "${vigiladas.size} ${if (vigiladas.size == 1) "app vigilada" else "apps vigiladas"}. Las demás siguen libres.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (lista == null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Midiendo cuánto usas cada app…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        items(ordenadas, key = { it.paquete }) { app ->
            FilaDeApp(
                app = app,
                activa = app.paquete in vigiladas,
                limites = remember(refresco, app.paquete, vigiladas) {
                    if (app.paquete in vigiladas) almacen.limites(app.paquete) else null
                },
                alCambiar = { marcada ->
                    // Se relee del almacen en vez de fiarse de lo que tenia la
                    // pantalla: una sincronizacion pudo anadir apps desde el
                    // chat mientras esto estaba abierto, y escribir la lista
                    // vieja las borraria.
                    val actuales = almacen.appsVigiladas()
                    vigiladas = if (marcada) actuales + app.paquete else actuales - app.paquete
                    almacen.guardarAppsVigiladas(vigiladas)
                    // Se apunta cuando se tocó aquí: si también se editó desde
                    // el chat, gana el cambio más reciente.
                    almacen.marcarCambio(app.paquete)
                    if (marcada) {
                        // Catorce consultas al sistema: fuera del hilo
                        // principal, o la interaccion mas importante de la app
                        // se queda pillada medio segundo.
                        alcance.launch(Dispatchers.IO) {
                            almacen.guardarLimites(app.paquete, limitesAutomaticosOFijos(almacen, lector, app.paquete))
                            refresco++
                        }
                    }
                },
                carga = if (app.paquete in vigiladas) cargas[app.paquete] else null,
                barraFija = remember(refresco, app.paquete) { almacen.barraFija(app.paquete) != null },
                bloqueoFijo = remember(refresco, app.paquete) { almacen.bloqueoFijo(app.paquete) != null },
                alCambiarBarra = { minutos ->
                    almacen.guardarBarraFija(app.paquete, minutos)
                    alcance.launch(Dispatchers.IO) {
                        almacen.guardarLimites(app.paquete, limitesAutomaticosOFijos(almacen, lector, app.paquete))
                        refresco++
                    }
                },
                alCambiarBloqueo = { minutos ->
                    almacen.guardarBloqueoFijo(app.paquete, minutos)
                    alcance.launch(Dispatchers.IO) {
                        almacen.guardarLimites(app.paquete, limitesAutomaticosOFijos(almacen, lector, app.paquete))
                        refresco++
                    }
                },
            )
        }

        if (hayMas && !verTodas) {
            item {
                TextButton(onClick = { verTodas = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver todas las apps", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Comprobaciones(almacen, context) }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { context.startActivity(Intent(context, Shell::class.java)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Abrir Disciplina", style = MaterialTheme.typography.labelLarge) }
            Text(
                "Tus tareas, el calendario y el chat. Es a donde lleva el icono del móvil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun limitesAutomaticosOFijos(almacen: Almacen, lector: LectorDeUso, paquete: String) =
    almacen.limitesPara(paquete, mediaDeUso(lector.minutosPorDia(paquete)), almacen.semanaDeLosLimites.coerceAtLeast(1))

@Composable
private fun Paso(numero: String, titulo: String, texto: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            numero,
            style = MaterialTheme.typography.titleLarge,
            color = ORO,
            modifier = Modifier.width(18.dp),
        )
        Column {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Text(
                texto,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilaDeApp(
    app: AppInstalada,
    activa: Boolean,
    limites: com.cerrojo.core.Limites?,
    alCambiar: (Boolean) -> Unit,
    carga: Pair<Float, String>?,
    barraFija: Boolean,
    bloqueoFijo: Boolean,
    alCambiarBarra: (Int?) -> Unit,
    alCambiarBloqueo: (Int?) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (activa) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium,
            )
            .clickable { alCambiar(!activa) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.nombre, style = MaterialTheme.typography.bodyLarge)
                Text(
                    // El dato que hace falta para decidir, justo donde se
                    // decide. Y dice de que periodo es: "al dia" a secas se
                    // lee como "hoy", que es otra cosa.
                    if (app.minutosDia > 0)
                        "${enHoras(app.minutosDia)} al día de media · hoy ${enHoras(app.minutosHoy)}"
                    else "Apenas la usas",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (app.minutosDia >= 60) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = activa, onCheckedChange = alCambiar)
        }

        if (activa) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (limites == null) {
                Text("Calculando tus límites…", style = MaterialTheme.typography.bodySmall)
            } else {
                // La barra de mana, en vivo.
                if (carga != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { carga.first },
                            modifier = Modifier.weight(1f).height(8.dp),
                            color = if (carga.first >= 1f) MaterialTheme.colorScheme.primary else ORO,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(carga.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Selector("Barra", limites.barraMin, 5..120, barraFija, alCambiarBarra)
                val rango = rangoDeBloqueo(limites.objetivoMin)
                Selector("Bloqueo", limites.bloqueoMin, rango, bloqueoFijo, alCambiarBloqueo)
                Text(
                    "El bloqueo puede ir de ${enHoras(rango.first)} a ${enHoras(rango.last)}: sale de tu media. " +
                        "Sin elegir, la barra es una sexta parte de la media y el bloqueo, el centro del rango.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** − valor + de 5 en 5 dentro de su rango. "Volver a automático" vuelve al calculo. */
@Composable
private fun Selector(titulo: String, valorMin: Int, rango: IntRange, fijo: Boolean, alCambiar: (Int?) -> Unit) {
    val paso = 5
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall)
            if (fijo) {
                Text(
                    "Volver a automático",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { alCambiar(null) },
                )
            }
        }
        OutlinedButton(
            onClick = { alCambiar(((valorMin - paso) / paso * paso).coerceIn(rango)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            enHoras(valorMin),
            style = MaterialTheme.typography.titleLarge,
            color = ORO,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
        OutlinedButton(
            onClick = { alCambiar(((valorMin + paso) / paso * paso).coerceIn(rango)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * El estado real del cerrojo. Va plegado porque no es lo que vienes a hacer,
 * pero no se quita: es el unico sitio donde se ve que algo dejo de funcionar,
 * y este proyecto ya ha tenido siete fallos que seguian diciendo que todo iba
 * bien.
 */
@Composable
private fun Comprobaciones(almacen: Almacen, context: android.content.Context) {
    Desplegable("Comprobaciones") {
        // El latido tiene que moverse solo. Un numero congelado se lee como
        // "todo bien" justo cuando el servicio acaba de morir, que es el unico
        // momento en que esta linea importa.
        var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                ahora = System.currentTimeMillis()
            }
        }

        fun hace(ms: Long): String {
            val s = (ahora - ms) / 1000
            return if (s < 90) "hace $s s" else "hace ${s / 60} min"
        }

        Linea(
            "El cerrojo está vivo",
            if (almacen.ultimaComprobacionMs == 0L) "Aún no ha dado señales"
            else "Última comprobación ${hace(almacen.ultimaComprobacionMs)}",
            almacen.ultimaComprobacionMs != 0L,
        )
        // Sin esta linea, un espejo de avisos atascado (esquema cambiado,
        // PostgREST fallando, o una consulta colgada que deja su guarda interna
        // sin liberarse nunca) fallaba en total silencio: el reenganche dejaba
        // de avisar y nada en la pantalla lo decia.
        Linea(
            "Avisos de tus tareas",
            if (almacen.ultimoEspejoOkMs == 0L) "Aún no ha leído nada con éxito"
            else "Leídos ${hace(almacen.ultimoEspejoOkMs)}",
            almacen.ultimoEspejoOkMs != 0L,
        )
        // La misma idea: si esto se queda parado, el coach lleva dias
        // razonando con numeros viejos y hasta ahora no habia forma de saberlo.
        Linea(
            "Tu uso llega al coach",
            if (almacen.ultimaSyncOkMs == 0L) "Aún sin sincronizar"
            else "Enviado ${hace(almacen.ultimaSyncOkMs)}",
            almacen.ultimaSyncOkMs != 0L,
        )

        val sesion = remember { com.cerrojo.datos.Sesion(context) }
        // Se mira si hay cuenta guardada, no si el token responde ahora mismo:
        // preguntarselo a la red diria "sin cuenta" cada vez que no hay
        // cobertura, que es mentira.
        val hayCuenta = remember { sesion.hayCuenta() }
        Linea(
            "Tu cuenta",
            if (hayCuenta) "Conectada. La web de dentro entra con esta misma."
            else "Sin conectar. Ciérrala y vuelve a abrir Seal para entrar.",
            hayCuenta,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Linea(
                "Notificaciones",
                "Las has denegado. El cerrojo sigue funcionando, pero no verás si se muere.",
                false,
            )
        }
    }
}

@Composable
private fun Linea(que: String, estado: String, bien: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (bien) "·" else "!",
            style = MaterialTheme.typography.titleMedium,
            color = if (bien) ORO else MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(10.dp),
        )
        Column {
            Text(que, style = MaterialTheme.typography.bodyMedium)
            Text(
                estado,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
