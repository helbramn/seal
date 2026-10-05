package com.cerrojo.core

const val BONUS_DESBLOQUEO_SEG = 5 * 60

/**
 * Una sola regla: el tope del dia. Cada segundo con la app delante suma, da
 * igual en cuantos ratos, y al llegar al tope se bloquea hasta el dia
 * siguiente.
 *
 * Antes habia ademas sesiones de "X minutos seguidos" con descanso, y desde la
 * v1.4 una pausa de 5 min que cerraba la sesion. El 5-oct el usuario lo tumbo:
 * cuatro ratos de 5 min no le bloqueaban nada, y dejar la app 5 min tras un
 * desbloqueo devolvia una sesion entera — el descanso se esquivaba solo. Lo
 * que pidio es esto: "si he llegado al consumo, se me tiene que bloquear".
 *
 * ENFRIANDO queda solo para leer estados guardados por versiones anteriores.
 */
enum class Estado { LIBRE, EN_SESION, ENFRIANDO, SIN_PRESUPUESTO }

@kotlinx.serialization.Serializable
data class EstadoApp(
    val estado: Estado = Estado.LIBRE,
    val segHoy: Int = 0,
    val extraHoySeg: Int = 0,
    val dia: String = "",
)

sealed interface Evento {
    data class Tick(val enPrimerPlano: Boolean, val ahoraMs: Long, val dia: String) : Evento

    /** Salida de friccion: 45 s de espera con la pantalla encendida. */
    data object Desbloqueo : Evento
}

fun avanzar(previo: EstadoApp, evento: Evento, limites: Limites): EstadoApp = when (evento) {
    // Solo desbloquea lo que esta bloqueado: si llega dos veces seguidas no
    // regala otros cinco minutos.
    is Evento.Desbloqueo -> if (!previo.bloqueada()) previo else previo.copy(
        estado = Estado.EN_SESION,
        extraHoySeg = previo.extraHoySeg + BONUS_DESBLOQUEO_SEG,
    )

    is Evento.Tick -> {
        var e = if (evento.dia != previo.dia) EstadoApp(dia = evento.dia) else previo

        // Un descanso de una version anterior: ya no existen, se libera.
        if (e.estado == Estado.ENFRIANDO) e = e.copy(estado = Estado.LIBRE)

        // Tambien con la app fuera: si el tope se baja a mano por debajo de lo
        // ya usado, queda bloqueada sin esperar a que se vuelva a abrir.
        if (e.estado != Estado.SIN_PRESUPUESTO && e.segHoy >= limites.presupuestoMin * 60 + e.extraHoySeg) {
            e = e.copy(estado = Estado.SIN_PRESUPUESTO)
        } else if (evento.enPrimerPlano && e.estado != Estado.SIN_PRESUPUESTO) {
            e = e.copy(estado = Estado.EN_SESION, segHoy = e.segHoy + 1)
            if (e.segHoy >= limites.presupuestoMin * 60 + e.extraHoySeg) {
                e = e.copy(estado = Estado.SIN_PRESUPUESTO)
            }
        }
        e
    }
}

fun EstadoApp.bloqueada(): Boolean = estado == Estado.SIN_PRESUPUESTO
