package com.cerrojo.core

const val BONUS_DESBLOQUEO_SEG = 5 * 60

/**
 * La barra de mana (pedida el 6-oct). Cada segundo con la app delante la
 * llena; fuera de la app se queda donde esta, no baja. Al llenarse, la app se
 * bloquea [Limites.bloqueoMin] minutos; al acabar el bloqueo la barra vuelve a
 * cero. Tambien vuelve a cero al empezar el dia.
 *
 * Por que: con el tope diario de la v1.6, que salia de su media (unas 2 h), en
 * un dia con 1 h 22 min de Instagram no le bloqueo nunca. La barra corta
 * mucho antes y varias veces al dia.
 *
 * ENFRIANDO es "barra llena, bloqueada". EN_SESION es "cargando".
 * SIN_PRESUPUESTO queda solo para leer estados de la v1.6.
 */
enum class Estado { LIBRE, EN_SESION, ENFRIANDO, SIN_PRESUPUESTO }

@kotlinx.serialization.Serializable
data class EstadoApp(
    val estado: Estado = Estado.LIBRE,
    /** Segundos de barra cargados. */
    val segBarra: Int = 0,
    /** Uso total de hoy, solo para enseñarlo. */
    val segHoy: Int = 0,
    /** Lo que da "Desbloquear igualmente": cinco minutos mas de barra. */
    val extraSeg: Int = 0,
    val finBloqueoMs: Long = 0L,
    val dia: String = "",
)

sealed interface Evento {
    data class Tick(val enPrimerPlano: Boolean, val ahoraMs: Long, val dia: String) : Evento

    /** Salida de friccion: 45 s de espera con la pantalla encendida. */
    data object Desbloqueo : Evento
}

fun avanzar(previo: EstadoApp, evento: Evento, limites: Limites): EstadoApp = when (evento) {
    // Solo desbloquea lo que esta bloqueado: si llega dos veces seguidas no
    // regala otros cinco minutos. Al gastarlos, vuelve a bloquear entero.
    is Evento.Desbloqueo -> if (!previo.bloqueada()) previo else previo.copy(
        estado = Estado.EN_SESION,
        extraSeg = previo.extraSeg + BONUS_DESBLOQUEO_SEG,
        finBloqueoMs = 0L,
    )

    is Evento.Tick -> {
        var e = previo
        if (evento.dia != e.dia) {
            // Dia nuevo: el uso de hoy a cero, y la barra tambien salvo que
            // este bloqueada — un bloqueo que cruza la medianoche se cumple.
            e = if (e.estado == Estado.ENFRIANDO) e.copy(segHoy = 0, dia = evento.dia)
            else EstadoApp(dia = evento.dia)
        }
        if (e.estado == Estado.SIN_PRESUPUESTO) e = e.copy(estado = Estado.LIBRE, segBarra = 0)

        if (e.estado == Estado.ENFRIANDO && evento.ahoraMs >= e.finBloqueoMs) {
            e = e.copy(estado = Estado.LIBRE, segBarra = 0, extraSeg = 0, finBloqueoMs = 0L)
        }

        if (evento.enPrimerPlano && !e.bloqueada()) {
            e = e.copy(estado = Estado.EN_SESION, segBarra = e.segBarra + 1, segHoy = e.segHoy + 1)
        }
        // Tambien con la app fuera: si se achica la barra por debajo de lo ya
        // cargado, bloquea sin esperar a que se vuelva a abrir.
        if (!e.bloqueada() && e.segBarra > 0 && e.segBarra >= limites.barraMin * 60 + e.extraSeg) {
            e = e.copy(
                estado = Estado.ENFRIANDO,
                finBloqueoMs = evento.ahoraMs + limites.bloqueoMin * 60_000L,
            )
        }
        e
    }
}

fun EstadoApp.bloqueada(): Boolean = estado == Estado.ENFRIANDO

/** De 0 a 1, para pintar la barra. */
fun EstadoApp.carga(limites: Limites): Float =
    if (bloqueada()) 1f
    else (segBarra.toFloat() / (limites.barraMin * 60 + extraSeg).coerceAtLeast(1)).coerceIn(0f, 1f)
