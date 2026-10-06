package com.cerrojo.datos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cerrojo.R
import com.cerrojo.ui.Principal
import com.cerrojo.ui.Shell
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val CANAL = "reenganche"
private const val AVISO_SESION = -1

/**
 * La web trata Europe/Madrid como una regla fija (`src/lib/dates.ts`), no la
 * zona del dispositivo, precisamente para que el movil y la Edge Function
 * coincidan por construccion y no por casualidad. Sin esto, un telefono con
 * otro huso —o solo mal ajustado— pediria el "hoy" equivocado a `task_logs` y
 * el espejo nunca veria las filas del dia que la web y el reenganche si ven.
 */
private val ZONA = TimeZone.getTimeZone("Europe/Madrid")

class EspejoDeAvisos(private val context: Context) {
    private val sesion = Sesion(context)
    private val almacen = Almacen(context)
    private val prefs = context.getSharedPreferences("espejo", Context.MODE_PRIVATE)

    private fun pendingIntent(destino: Class<*>, ajustes: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, if (ajustes) 1 else 0,
            Intent(context, destino)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Principal.EXTRA_AJUSTES, ajustes),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Todo va dentro de un try: esto corre en un hilo pelado lanzado por el
     * servicio, y una excepcion sin capturar ahi mata el proceso entero. Como
     * START_STICKY lo reinicia, el fallo se repetiria en bucle y el cerrojo
     * dejaria de vigilar por culpa de una notificacion.
     */
    @Synchronized
    fun comprobar() = try {
        comprobarDeVerdad()
    } catch (_: Exception) {
    }

    private fun comprobarDeVerdad() {
        // El "hoy" de la app es el dia logico: acaba a settings.inicio_dia_minutos
        // (05:00), no a medianoche. Sin esto, a las 00:30 el espejo pedia las
        // filas del dia nuevo y se perdia la tarea de esa hora, que es del dia
        // anterior, y los avisos de lo que sigue sin hacer pasada la medianoche.
        val hoy = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = ZONA }
            .format(Date(System.currentTimeMillis() - corteMin() * 60_000L))
        olvidarDiasPasados(hoy)

        val json = sesion.obtener(
            "/rest/v1/task_logs?select=task_id,status,reminded_at,toque_en,aviso_titulo,aviso_texto&log_date=eq.$hoy"
        )
        if (json == null) {
            avisarSiSePerdioLaSesion()
            return
        }
        // Se lee con exito, con filas o sin ellas: es la unica señal de que
        // el espejo sigue funcionando de verdad. Sin ella, un cambio de
        // esquema o un error de PostgREST paraba el reenganche entero sin
        // dejar ningun rastro visible en Ajustes.
        almacen.ultimoEspejoOkMs = System.currentTimeMillis()
        prefs.edit().putBoolean("sesionAvisada", false).apply()
        val filas = JSONArray(json)

        crearCanal()
        for (i in 0 until filas.length()) {
            val fila = filas.getJSONObject(i)
            val id = fila.optString("task_id")
            if (id.isEmpty()) continue
            val estado = fila.optString("status")
            // La huella incluye el texto: el servidor manda varios avisos por
            // tarea y dia —el de la hora, los tirones de orejas, el fallo— y
            // sin el texto dentro, el segundo y los siguientes se tomaban por
            // repetidos y no se mostraban nunca.
            val huella = estado + "|" + fila.optString("reminded_at") +
                "|" + fila.optString("toque_en") + "|" + fila.optString("aviso_texto")
            if (prefs.getString("visto:$id:$hoy", null) == huella) continue
            prefs.edit().putString("visto:$id:$hoy", huella).apply()

            // El texto lo escribe el servidor y viene en la fila. Antes cada
            // canal se inventaba el suyo, y el de aqui ni siquiera decia que
            // tarea era: "Tienes una obligatoria sin marcar" con siete tareas
            // activas no dice nada. Los textos de abajo son el respaldo para
            // una fila escrita por una version anterior del reenganche.
            val delServidor = fila.optString("aviso_texto").takeIf { it.isNotEmpty() }
            val texto = when {
                delServidor != null -> delServidor
                estado == "fallida" -> "Has fallado una obligatoria. La barra ya lo ha notado."
                // Posponer es una decision del usuario, no un descuido: darle
                // la lata igual seria castigarle por haber hecho algo.
                estado == "pospuesta" || estado == "hecha" -> continue
                !fila.isNull("reminded_at") -> "Tienes una obligatoria sin marcar. ¿A qué esperas?"
                else -> continue
            }
            // Marcada o pospuesta no se avisa, aunque el servidor haya dejado
            // texto de un aviso anterior del mismo dia.
            if (estado == "pospuesta" || estado == "hecha") continue

            val titulo = fila.optString("aviso_titulo").takeIf { it.isNotEmpty() } ?: "Disciplina"
            context.getSystemService(NotificationManager::class.java)
                .notify(id.hashCode(), NotificationCompat.Builder(context, CANAL)
                    .setContentTitle(titulo)
                    .setContentText(texto)
                    // Sin esto Android corta el texto en una linea y el aviso
                    // pierde justo lo que lo hace util: la hora y el retraso.
                    .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                    .setSmallIcon(R.drawable.ic_aviso)
                    .setAutoCancel(true)
                    // El reenganche existe para que se pueda actuar sobre el
                    // aviso, no solo leerlo: sin esto, "¿A que esperas?" no
                    // llevaba a ningun sitio.
                    .setContentIntent(pendingIntent(Shell::class.java))
                    .build())
        }
    }

    /**
     * Si hay cuenta guardada pero ya no se consigue token, la sesion murio y el
     * reenganche esta callado. Sin este aviso, dejaria de dar la lata en
     * silencio y el usuario lo tomaria por buena conducta suya.
     */
    private fun avisarSiSePerdioLaSesion() {
        if (!sesion.hayCuenta() || prefs.getBoolean("sesionAvisada", false)) return

        // No se vuelve a pedir el token. El intento que acaba de fallar ya dejo
        // dicho por que, y repetirlo lanzaria una segunda peticion cuyo codigo
        // puede no coincidir con el de la primera: Supabase limita los intentos
        // fallidos seguidos, y un 429 en la repeticion haria pasar por problema
        // del servidor lo que era una sesion revocada. El aviso se perderia
        // para siempre, que es exactamente el fallo que esto evita.
        //
        // Solo cuenta un rechazo de credenciales. Sin cobertura no es sesion
        // muerta, y un error del servidor tampoco: avisar en cada tunel
        // volveria el aviso ruido de fondo, y entonces no serviria el dia que
        // la sesion muera de verdad.
        if (sesion.ultimoFalloDeSesion != Sesion.Fallo.RECHAZADA) return

        prefs.edit().putBoolean("sesionAvisada", true).apply()
        // Se olvida DESPUES de decidir avisar, nunca antes: borrar primero
        // dejaria sin cuenta a quien tiene que dar el aviso. Y hace falta
        // olvidarla para que la pantalla de ajustes ofrezca entrar de nuevo.
        sesion.olvidar()
        crearCanal()
        context.getSystemService(NotificationManager::class.java)
            .notify(AVISO_SESION, NotificationCompat.Builder(context, CANAL)
                .setContentTitle("Seal")
                .setContentText("Se perdió la conexión con tu cuenta: entra en Ajustes para reconectarla.")
                .setSmallIcon(R.drawable.ic_aviso)
                .setAutoCancel(true)
                // "Entra en Ajustes" tiene que llevar a Ajustes de verdad, no a
                // Principal a secas: desde el arranque directo a Shell (spec
                // de Principal), eso rebotaria a la web sin pasar por el
                // formulario de entrada que el aviso promete.
                .setContentIntent(pendingIntent(Principal::class.java, ajustes = true))
                .build())
    }

    /**
     * Inicio del dia lógico en minutos, leido de settings una vez por hora y
     * guardado: sin red se sigue con el ultimo bueno (o las 05:00 de serie).
     */
    private fun corteMin(): Int {
        val ahora = System.currentTimeMillis()
        if (ahora - prefs.getLong("corteLeidoMs", 0L) > 3_600_000L) {
            sesion.obtener("/rest/v1/settings?select=inicio_dia_minutos")
                ?.let { runCatching { JSONArray(it).getJSONObject(0).getInt("inicio_dia_minutos") }.getOrNull() }
                ?.let { prefs.edit().putInt("corteMin", it).putLong("corteLeidoMs", ahora).apply() }
        }
        return prefs.getInt("corteMin", 300)
    }

    /** Las marcas de "ya avisado" son por dia; las de ayer no valen para nada. */
    private fun olvidarDiasPasados(hoy: String) {
        if (prefs.getString("dia", null) == hoy) return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("visto:") }.forEach { editor.remove(it) }
        editor.putString("dia", hoy).apply()
    }

    private fun crearCanal() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Reenganche", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
