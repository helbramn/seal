package com.cerrojo.datos

import android.content.Context
import com.cerrojo.core.EstadoApp
import com.cerrojo.core.Limites
import com.cerrojo.core.SUELO_POR_DEFECTO_MIN
import com.cerrojo.core.limitesDe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// El nombre del fichero de preferencias NO se renombra con la app. Es la
// clave con la que Android guarda lo que hay dentro: cambiarlo abre un fichero
// vacio y el movil pierde los limites, las apps vigiladas y la fecha de
// instalacion que cuenta las semanas. Que se llame "cerrojo" no lo ve nadie.
private const val FICHERO = "cerrojo"
private const val URL_WEB_POR_DEFECTO = "https://app-disciplina-theta.vercel.app"

class Almacen(context: Context) {
    private val prefs = context.getSharedPreferences(FICHERO, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun appsVigiladas(): List<String> =
        prefs.getStringSet("apps", emptySet())!!.toList().sorted()

    fun guardarAppsVigiladas(paquetes: List<String>) =
        prefs.edit().putStringSet("apps", paquetes.toSet()).apply()

    /**
     * Un valor corrupto o de una version anterior degrada al estado por
     * defecto, nunca revienta: esto lo lee el servicio de vigilancia una vez
     * por segundo, y una excepcion ahi mataria al hilo encargado de bloquear.
     * Perder el estado de una app es malo; dejar de vigilar es peor.
     */
    fun estado(paquete: String): EstadoApp =
        prefs.getString("estado:$paquete", null)
            ?.let { runCatching { json.decodeFromString<EstadoApp>(it) }.getOrNull() }
            ?: EstadoApp()

    fun guardarEstado(paquete: String, estado: EstadoApp) =
        prefs.edit().putString("estado:$paquete", json.encodeToString(estado)).apply()

    fun limites(paquete: String): Limites? =
        prefs.getString("limites:$paquete", null)
            ?.let { runCatching { json.decodeFromString<Limites>(it) }.getOrNull() }

    /** Borrarlos obliga al servicio a recalcularlos en la siguiente vuelta. */
    fun olvidarLimites(paquete: String) =
        prefs.edit().remove("limites:$paquete").apply()

    fun guardarLimites(paquete: String, limites: Limites) =
        prefs.edit().putString("limites:$paquete", json.encodeToString(limites)).apply()

    fun suelo(paquete: String): Int = prefs.getInt("suelo:$paquete", SUELO_POR_DEFECTO_MIN)

    fun guardarSuelo(paquete: String, minutos: Int) =
        prefs.edit().putInt("suelo:$paquete", minutos).apply()

    /**
     * Tamaño de la barra y duracion del bloqueo elegidos a mano en Ajustes, o
     * null si van en automatico (de tu media). Claves nuevas a proposito: el
     * "tope:" de la v1.6 era un tope diario (150 min) y leido como barra no
     * bloquearia nunca.
     */
    fun barraFija(paquete: String): Int? = prefs.getInt("barra:$paquete", 0).takeIf { it > 0 }
    fun bloqueoFijo(paquete: String): Int? = prefs.getInt("bloqueo:$paquete", 0).takeIf { it > 0 }

    fun guardarBarraFija(paquete: String, minutos: Int?) = guardarFijo("barra:$paquete", minutos)
    fun guardarBloqueoFijo(paquete: String, minutos: Int?) = guardarFijo("bloqueo:$paquete", minutos)

    private fun guardarFijo(clave: String, minutos: Int?) =
        prefs.edit().apply { if (minutos == null) remove(clave) else putInt(clave, minutos) }.apply()

    /** El unico sitio que junta media, suelo y lo elegido a mano. */
    fun limitesPara(paquete: String, media: Int, semana: Int): Limites =
        limitesDe(media, semana, suelo(paquete), barraFija(paquete), bloqueoFijo(paquete))

    /**
     * Cuando se toco por ultima vez la vigilancia de esta app EN ESTE MOVIL.
     * El usuario puede editar desde el chat y desde aqui, asi que hace falta
     * saber cual de los dos cambios es mas reciente: gana el ultimo.
     */
    fun cambiadoEn(paquete: String): Long = prefs.getLong("cambiado:$paquete", 0L)

    /**
     * Se guarda al segundo, sin milisegundos: el servidor devuelve la suya
     * recortada a segundos, y si aqui quedaran los milisegundos la marca local
     * seria siempre mayor y cada app tocada se reenviaria en cada sincronizacion
     * para siempre.
     */
    fun marcarCambio(paquete: String, cuandoMs: Long = System.currentTimeMillis()) =
        prefs.edit().putLong("cambiado:$paquete", cuandoMs / 1000L * 1000L).apply()

    /** Ultimo dia (yyyy-MM-dd) cuyo uso ya se subio. */
    var ultimoDiaSubido: String
        get() = prefs.getString("ultimoDiaSubido", "")!!
        set(valor) = prefs.edit().putString("ultimoDiaSubido", valor).apply()

    /** Ultima sincronizacion con el servidor que salio bien. */
    var ultimaSyncOkMs: Long
        get() = prefs.getLong("syncOk", 0L)
        set(valor) = prefs.edit().putLong("syncOk", valor).apply()

    var instaladoEl: Long
        get() = prefs.getLong("instaladoEl", 0L).let {
            if (it != 0L) it else System.currentTimeMillis().also { ahora ->
                prefs.edit().putLong("instaladoEl", ahora).apply()
            }
        }
        set(valor) = prefs.edit().putLong("instaladoEl", valor).apply()

    var urlWeb: String
        get() = prefs.getString("urlWeb", URL_WEB_POR_DEFECTO)!!
        set(valor) = prefs.edit().putString("urlWeb", valor).apply()

    /**
     * El usuario dio por buenos los dos permisos de MIUI que no se pueden
     * comprobar por API. Sin esto, el asistente reaparecería en cada arranque.
     */
    var asistenteHecho: Boolean
        get() = prefs.getBoolean("asistente", false)
        set(valor) = prefs.edit().putBoolean("asistente", valor).apply()

    /** Semana con la que se calcularon los limites vigentes. */
    var semanaDeLosLimites: Int
        get() = prefs.getInt("semana", 0)
        set(valor) = prefs.edit().putInt("semana", valor).apply()

    /**
     * Minutos tras la medianoche a los que empieza el dia nuevo: el mismo
     * corte que la web (`settings.inicio_dia_minutos`, 05:00). Lo refresca el
     * espejo de avisos una vez por hora. Antes la barra se vaciaba a las 00:00
     * y la web cerraba el dia a las 05:00.
     */
    var inicioDiaMin: Int
        get() = prefs.getInt("inicioDiaMin", 300)
        set(valor) = prefs.edit().putInt("inicioDiaMin", valor).apply()

    var ultimaComprobacionMs: Long
        get() = prefs.getLong("latido", 0L)
        set(valor) = prefs.edit().putLong("latido", valor).apply()

    /**
     * Ultima vez que el espejo de avisos leyo `task_logs` con exito (con filas
     * o sin ellas). Sin esto, un cambio de esquema o un error de PostgREST
     * paraba el reenganche entero sin dejar ningun rastro visible: el catch
     * vacio de `EspejoDeAvisos.comprobar()` se lo tragaba en total silencio.
     */
    var ultimoEspejoOkMs: Long
        get() = prefs.getLong("espejoOk", 0L)
        set(valor) = prefs.edit().putLong("espejoOk", valor).apply()
}
