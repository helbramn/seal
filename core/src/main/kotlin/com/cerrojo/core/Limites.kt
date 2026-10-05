package com.cerrojo.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt

const val SUELO_POR_DEFECTO_MIN = 20
const val MEDIA_POR_DEFECTO_MIN = 30
const val DIAS_MINIMOS_DE_HISTORIAL = 3

@kotlinx.serialization.Serializable
data class Limites(
    val objetivoMin: Int,
    val presupuestoMin: Int,
)

fun mediaDeUso(minutosPorDia: List<Int>): Int {
    if (minutosPorDia.size < DIAS_MINIMOS_DE_HISTORIAL) return MEDIA_POR_DEFECTO_MIN
    val orden = minutosPorDia.sorted()
    val medio = orden.size / 2
    return if (orden.size % 2 == 1) orden[medio] else (orden[medio - 1] + orden[medio]) / 2
}

/**
 * Semanas desde la instalacion contando lunes cruzados, no bloques de 7 dias:
 * la spec dice que los objetivos bajan los lunes. La semana de la instalacion
 * es la 1.
 */
fun semana(instalacion: LocalDate, hoy: LocalDate): Int =
    ChronoUnit.WEEKS.between(
        instalacion.with(DayOfWeek.MONDAY),
        hoy.with(DayOfWeek.MONDAY),
    ).toInt() + 1

fun limitesDe(media: Int, semana: Int, sueloMin: Int = SUELO_POR_DEFECTO_MIN): Limites {
    require(semana >= 1) { "la semana empieza en 1" }
    val bruto = media * 0.9.pow(semana - 1)
    return limitesConObjetivo(maxOf(sueloMin, bruto.roundToInt()))
}

/**
 * Limites a partir de un tope diario. Lo usa el calculo automatico y tambien
 * el tope que el usuario elige a mano para una app. Ya no hay sesiones ni
 * descansos (ver Estados.kt): el tope del dia es la unica regla.
 */
fun limitesConObjetivo(objetivoMin: Int): Limites {
    val objetivo = objetivoMin.coerceAtLeast(1)
    return Limites(objetivo, objetivo)
}
