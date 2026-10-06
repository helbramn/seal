package com.cerrojo.servicio

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.cerrojo.R
import androidx.core.app.ServiceCompat
import com.cerrojo.core.Evento
import com.cerrojo.core.bloqueada
import com.cerrojo.core.avanzar
import com.cerrojo.core.carga
import com.cerrojo.core.mediaDeUso
import com.cerrojo.core.semana
import com.cerrojo.datos.Almacen
import com.cerrojo.sistema.LectorDeUso
import com.cerrojo.ui.Principal
import com.cerrojo.ui.PantallaDeBloqueo
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private const val CANAL = "vigilancia"
private const val ID_NOTIFICACION = 1
private const val PERIODO_MS = 1_000L
private const val LATIDO_MS = 10_000L

/**
 * Android 10+ descarta en silencio el arranque de una Activity desde segundo
 * plano si falta el permiso de superposicion. Si tras este numero de vueltas la
 * app bloqueada sigue delante, es que la pantalla no llego a aparecer: se
 * reintenta en vez de darla por mostrada.
 */
private const val TICS_PARA_REINTENTAR_BLOQUEO = 3

/**
 * Ciclos completos de reintento (cada uno de [TICS_PARA_REINTENTAR_BLOQUEO]
 * vueltas) en los que la app bloqueada siguio delante pese a haber pedido
 * mostrar el bloqueo: la unica señal posible de que MIUI revoco "ventanas
 * emergentes en segundo plano", que no se puede consultar por API.
 */
private const val REINTENTOS_FALLIDOS_PARA_AVISAR = 3

class ServicioDeVigilancia : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var almacen: Almacen
    private lateinit var lector: LectorDeUso
    private val formatoDia = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var ultimoAvisoDeLatido = 0L
    private var bloqueoMostradoPara: String? = null
    private var ticsDesdeElBloqueo = 0
    private var reintentosFallidosSeguidos = 0
    private val sincronizador by lazy { com.cerrojo.datos.SincronizadorDeUso(this) }
    private val espejo by lazy { com.cerrojo.datos.EspejoDeAvisos(this) }
    private var ultimoEspejo = 0L
    private var ultimaSync = 0L

    @Volatile
    private var sincronizando = false

    @Volatile
    private var mirandoAvisos = false

    @Volatile
    private var recalculando = false

    override fun onCreate() {
        super.onCreate()
        almacen = Almacen(this)
        lector = LectorDeUso(this)
        crearCanal()
        asegurarPrimerPlano()
        // Defensivo ademas de en el arranque real: si el sistema alguna vez
        // tira la alarma sin que haya habido reinicio, cada vez que el
        // servicio vuelve a levantarse por su cuenta se reactiva sola.
        armarVigilante(this)
        handler.post(vuelta)
    }

    /**
     * `startForegroundService` arma un plazo para llamar a `startForeground`
     * que hay que cumplir CADA VEZ que se entra al servicio, no solo la
     * primera. `Principal.onResume` llama a `arrancar()` en cada apertura de
     * la app, casi siempre contra un servicio que ya esta corriendo: si solo
     * se llamase desde `onCreate`, esas llamadas de `onStartCommand` no
     * cumplirian nunca ese plazo y el sistema mataria el proceso con
     * `RemoteServiceException` unos diez segundos despues de abrir Seal.
     */
    private fun asegurarPrimerPlano() {
        ServiceCompat.startForeground(
            this, ID_NOTIFICACION, notificacion("arrancando…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private val vuelta = object : Runnable {
        override fun run() {
            // Throwable, no Exception: un OutOfMemoryError en el camino del
            // JSON de limites tambien tiene que dejar la vuelta viva, o el
            // bloqueo entero se para por un fallo que no era ni una
            // excepcion de las que se esperaban capturar.
            try { comprobar() } catch (_: Throwable) { }
            handler.postDelayed(this, PERIODO_MS)
        }
    }

    private fun comprobar() {
        val ahora = System.currentTimeMillis()
        val dia = diaLogico(ahora)
        // Con la pantalla apagada el sistema sigue diciendo cual fue la ultima
        // app en primer plano; sin esta comprobacion el reloj correria con el
        // movil en el bolsillo (spec 4.2).
        val encendida = getSystemService(PowerManager::class.java).isInteractive
        val delante = if (encendida) lector.appEnPrimerPlano() else null
        revisarSemana()
        // Castigo a 0 de Voluntad: todas las vigiladas bloqueadas (ver Almacen).
        val castigo = almacen.castigoHasta > 0

        for (paquete in almacen.appsVigiladas()) {
            val limites = almacen.limites(paquete) ?: continue
            val previo = almacen.estado(paquete)
            val nuevo = avanzar(previo, Evento.Tick(paquete == delante, ahora, dia), limites)
            if (nuevo != previo) almacen.guardarEstado(paquete, nuevo)

            // Solo al entrar en bloqueo: la vuelta es cada segundo y relanzar
            // la Activity 60 veces por minuto se comeria la bateria y pisaria
            // la cuenta atras de los 45 s. Pero si la app bloqueada sigue
            // delante varias vueltas despues, la pantalla no llego a salir y
            // hay que reintentarlo: darla por mostrada dejaria la app sin
            // bloquear hasta que el usuario cambiase de aplicacion.
            if (paquete == delante && (nuevo.bloqueada() || castigo)) {
                if (bloqueoMostradoPara != paquete) {
                    bloqueoMostradoPara = paquete
                    ticsDesdeElBloqueo = 0
                    PantallaDeBloqueo.mostrar(this, paquete, nuevo.estado, castigo)
                } else if (++ticsDesdeElBloqueo >= TICS_PARA_REINTENTAR_BLOQUEO) {
                    bloqueoMostradoPara = null
                    // La app bloqueada seguia delante tras un ciclo entero de
                    // reintento: la pantalla no llego a aparecer. Se cuenta
                    // para que el latido pueda decirlo en vez de seguir
                    // mudo mientras nada se bloquea de verdad.
                    reintentosFallidosSeguidos++
                }
            } else if (bloqueoMostradoPara == paquete) {
                bloqueoMostradoPara = null
                // Dejo de estar delante mientras se esperaba el bloqueo: o la
                // pantalla salio y se lo llevo, o el usuario cambio de app por
                // su cuenta. Cualquiera de los dos vale como señal de que el
                // camino de bloqueo sigue funcionando.
                reintentosFallidosSeguidos = 0
            }
        }

        // Subir el uso y bajar las apps que el usuario eligio desde el chat.
        // Cada cuarto de hora basta: son datos para que el coach hable con
        // fundamento, no para bloquear — eso va con la copia local.
        if (ahora - ultimaSync > 15 * 60_000L && !sincronizando) {
            ultimaSync = ahora
            sincronizando = true
            Thread {
                try {
                    sincronizador.sincronizar()
                } catch (_: Throwable) {
                } finally {
                    sincronizando = false
                }
            }.start()
        }

        // La misma guarda que el recalculo semanal: sin ella, una red que no
        // contesta acumularia un hilo colgado cada cinco minutos, y dos a la
        // vez pueden notificar el mismo aviso dos veces.
        // Cada minuto, no cada cinco: este es el unico canal de avisos del movil,
        // y con cinco el "en 10 min" del 5-oct llego a falta de 6.
        if (ahora - ultimoEspejo > 60_000L && !mirandoAvisos) {
            ultimoEspejo = ahora
            mirandoAvisos = true
            Thread {
                try {
                    espejo.comprobar()
                } catch (_: Throwable) {
                    // `espejo` es `by lazy`: su constructor corre aqui dentro,
                    // en un hilo pelado, y comprobar() solo protege lo que
                    // pasa DESPUES de construirlo. Sin este catch, un fallo en
                    // la construccion (SharedPreferences corruptas, etc.) mata
                    // el proceso, START_STICKY lo revive y el fallo se repite.
                } finally {
                    mirandoAvisos = false
                }
            }.start()
        }

        if (ahora - ultimoAvisoDeLatido > LATIDO_MS) {
            ultimoAvisoDeLatido = ahora
            almacen.ultimaComprobacionMs = ahora
            val nm = getSystemService(NotificationManager::class.java)
            val barra = barraMasLlena()
            nm.notify(ID_NOTIFICACION, notificacion(barra?.first ?: textoDeLatido(ahora), barra?.second))
        }
    }

    /**
     * El latido lleva la hora de la ultima comprobacion, no un texto fijo: una
     * notificacion identica cada vez no distingue un servicio vivo de uno
     * atascado, y comprobar que sigue en pie es justamente para lo que esta.
     *
     * Si MIUI revoca el acceso al uso —lo hace— las consultas dejan de devolver
     * eventos sin lanzar ninguna excepcion: nada se bloquearia y nada lo diria.
     * Por eso el latido tambien vigila el permiso. `tienePermisoDeUso()` prueba
     * el acceso real cuando el sistema esta en MODE_DEFAULT, asi que aqui solo
     * llega `null` si el propio metodo llegara a cambiar; con el permiso
     * realmente denegado siempre da `false` y enciende el aviso.
     */
    private fun textoDeLatido(ahora: Long): String = when {
        lector.tienePermisoDeUso() == false -> "sin permiso de uso — abre Seal"
        // El contador es la unica señal posible de que "ventanas emergentes en
        // segundo plano" se revoco: no hay API para consultarlo directamente.
        reintentosFallidosSeguidos >= REINTENTOS_FALLIDOS_PARA_AVISAR ->
            "el bloqueo no consigue aparecer — revisa \"mostrar sobre otras apps\""
        else -> "vigilando · última comprobación ${formatoHora.format(Date(ahora))}"
    }

    /**
     * La barra de mana de la app vigilada mas cargada, para la notificacion
     * fija: se ve como se llena sin abrir Seal. Si algo va mal (permiso,
     * bloqueo que no aparece) manda el aviso del latido, no la barra.
     */
    private fun barraMasLlena(): Pair<String, Int>? {
        if (lector.tienePermisoDeUso() == false || reintentosFallidosSeguidos >= REINTENTOS_FALLIDOS_PARA_AVISAR) return null
        val (paquete, estado, limites) = almacen.appsVigiladas()
            .mapNotNull { p -> almacen.limites(p)?.let { Triple(p, almacen.estado(p), it) } }
            .maxByOrNull { (_, e, l) -> e.carga(l) } ?: return null
        val nombre = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquete, 0)).toString()
        }.getOrDefault(paquete)
        val pct = (estado.carga(limites) * 100).toInt()
        val texto = if (estado.bloqueada())
            "$nombre bloqueada hasta las ${SimpleDateFormat("HH:mm", Locale.US).format(Date(estado.finBloqueoMs))}"
        else "$nombre · ${estado.segBarra / 60} de ${limites.barraMin} min de barra"
        return texto to pct
    }

    /** El dia nuevo (barra a cero) empieza a la misma hora que en la web. */
    private fun diaLogico(ahora: Long): String =
        formatoDia.format(Date(ahora - almacen.inicioDiaMin * 60_000L))

    /**
     * Recalcular cuesta 14 consultas por app, asi que solo se hace cuando el
     * numero de semana cambia de verdad, o cuando falta algun limite porque se
     * acaba de anadir una app. En su propio hilo para no frenar la vuelta de
     * cada segundo.
     */
    private fun revisarSemana() {
        // Sin esta guarda se lanzaria un hilo por vuelta mientras el anterior
        // sigue trabajando: el recalculo tarda segundos (14 consultas por app)
        // y durante ese rato los limites aun no estan completos, asi que cada
        // tic arrancaria otro recalculo. Justo al elegir apps por primera vez.
        if (recalculando) return
        val instalacion = Instant.ofEpochMilli(almacen.instaladoEl)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        // Un reloj que retrocede (cambio de hora, backup restaurado) puede
        // hacer que `instaladoEl` quede en el futuro y `semana()` devuelva 0 o
        // menos; limitesDe() exige semana >= 1 y reventaria dentro del hilo,
        // tragado por el catch de mas abajo, dejando `limites == null` para
        // siempre y sin bloquear nada nunca. PantallaDeAjustes ya se protegia
        // asi para el mismo calculo.
        val n = semana(instalacion, LocalDate.now()).coerceAtLeast(1)
        val completos = almacen.appsVigiladas().all { almacen.limites(it) != null }
        if (n == almacen.semanaDeLosLimites && completos) return
        recalculando = true
        Thread {
            try {
                for (paquete in almacen.appsVigiladas()) {
                    almacen.guardarLimites(
                        paquete,
                        almacen.limitesPara(paquete, mediaDeUso(lector.minutosPorDia(paquete)), n),
                    )
                }
                // Solo se marca la semana como resuelta si el bucle entero
                // termino sin fallos. Escribirlo antes (como estaba) daba la
                // semana por hecha aunque el recalculo fallase a medias: con
                // el catch vacio de debajo, un solo fallo dejaba las apps con
                // los limites de la semana pasada hasta el lunes siguiente, en
                // silencio.
                almacen.semanaDeLosLimites = n
            } catch (_: Throwable) {
                // Una excepcion sin capturar en un Thread pelado mata el proceso
                // entero, y START_STICKY lo reiniciaria contra el mismo fallo.
                // Al no escribir semanaDeLosLimites aqui, la siguiente vuelta
                // lo vuelve a intentar en vez de darlo por bueno.
            } finally {
                recalculando = false
            }
        }.start()
    }

    private fun crearCanal() {
        val canal = NotificationChannel(CANAL, "Vigilancia", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacion(texto: String, progreso: Int? = null): Notification =
        NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Seal")
            .setContentText(texto)
            .apply { if (progreso != null) setProgress(100, progreso, false) }
            .setSmallIcon(R.drawable.ic_aviso)
            .setOngoing(true)
            // Sin esto "sin permiso de uso — abre Seal" mandaba a abrir una
            // app que tocarla no hacia nada: el propio aviso decia que hacer y
            // luego no dejaba hacerlo.
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, Principal::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        asegurarPrimerPlano()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacks(vuelta); super.onDestroy() }

    companion object {
        private const val INTERVALO_VIGILANTE_MS = 15 * 60_000L

        fun arrancar(context: Context) {
            context.startForegroundService(Intent(context, ServicioDeVigilancia::class.java))
        }

        /**
         * Red de seguridad de la spec (§12): si MIUI mata el proceso y nadie
         * abre la app ni reinicia el movil, hasta ahora no habia ningun camino
         * de vuelta. Inexacta y repetida por el propio sistema — no hace falta
         * SCHEDULE_EXACT_ALARM ni volver a armarla cada vez que dispara, solo
         * en los sitios desde los que se llama (arranque del movil y arranque
         * del propio servicio).
         */
        fun armarVigilante(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, ReceptorDeArranque::class.java).setAction(ACCION_VIGILAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // _WAKEUP, no la variante normal: un vigilante que solo dispara si
            // el movil ya estaba despierto por otra razon no es una red de
            // seguridad de verdad. Sigue sin hacer falta SCHEDULE_EXACT_ALARM
            // — eso es por usar `setInexactRepeating`, no por el tipo de reloj.
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVALO_VIGILANTE_MS,
                INTERVALO_VIGILANTE_MS,
                pi,
            )
        }
    }
}
