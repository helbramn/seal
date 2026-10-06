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
    /** Tu media diaria con la app, bajando cada lunes. De ella salen los otros dos. */
    val objetivoMin: Int,
    /** Minutos de uso que llenan la barra. */
    val barraMin: Int,
    /** Lo que dura el bloqueo cuando se llena. */
    val bloqueoMin: Int,
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

fun limitesDe(
    media: Int,
    semana: Int,
    sueloMin: Int = SUELO_POR_DEFECTO_MIN,
    barraFija: Int? = null,
    bloqueoFijo: Int? = null,
): Limites {
    require(semana >= 1) { "la semana empieza en 1" }
    val bruto = media * 0.9.pow(semana - 1)
    return limitesConObjetivo(maxOf(sueloMin, bruto.roundToInt()), barraFija, bloqueoFijo)
}

private fun aCinco(min: Int) = ((min + 2) / 5) * 5

/**
 * Entre que valores puede elegir cuanto dura el bloqueo: de un tercio de su
 * media a la media entera. Usar mucho la app = bloqueos mas largos.
 */
fun rangoDeBloqueo(objetivoMin: Int): IntRange {
    val desde = aCinco(maxOf(15, objetivoMin / 3))
    val hasta = aCinco(maxOf(30, objetivoMin))
    return desde..maxOf(desde, hasta)
}

/**
 * La barra y el bloqueo a partir de la media. Lo que el usuario haya elegido a
 * mano manda; si no, la barra es una sexta parte de la media (entre 10 y 60
 * min) y el bloqueo, el centro de su rango.
 */
fun limitesConObjetivo(objetivoMin: Int, barraFija: Int? = null, bloqueoFijo: Int? = null): Limites {
    val objetivo = objetivoMin.coerceAtLeast(1)
    val barra = barraFija?.coerceAtLeast(1) ?: aCinco(objetivo / 6).coerceIn(10, 60)
    val rango = rangoDeBloqueo(objetivo)
    val bloqueo = (bloqueoFijo ?: aCinco((rango.first + rango.last) / 2)).coerceIn(rango)
    return Limites(objetivo, barra, bloqueo)
}
