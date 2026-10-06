package com.cerrojo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LimitesTest {
    @Test fun `la media es la mediana de los dias con datos`() {
        assertEquals(30, mediaDeUso(listOf(10, 30, 90, 25, 35)))
    }

    @Test fun `sin historial suficiente usa el valor por defecto`() {
        assertEquals(MEDIA_POR_DEFECTO_MIN, mediaDeUso(listOf(80, 90)))
    }

    @Test fun `con un numero par de dias con datos la media es el promedio de los dos centrales`() {
        // Sin este caso la rama par de mediaDeUso nunca se ejecutaba: todos
        // los demas tests usan listas de longitud impar.
        assertEquals(25, mediaDeUso(listOf(10, 40, 20, 30)))
    }

    @Test fun `la primera semana el objetivo es la media`() {
        val l = limitesDe(media = 80, semana = 1)
        assertEquals(80, l.objetivoMin)
    }

    @Test fun `el objetivo baja un diez por ciento por semana`() {
        assertEquals(52, limitesDe(80, 5).objetivoMin)
        assertEquals(34, limitesDe(80, 9).objetivoMin)
    }

    @Test fun `el objetivo nunca baja del suelo`() {
        assertEquals(20, limitesDe(80, 14).objetivoMin)
        assertEquals(20, limitesDe(80, 40).objetivoMin)
        assertEquals(45, limitesDe(80, 40, sueloMin = 45).objetivoMin)
    }

    @Test fun `la semana avanza los lunes, no a los siete dias de instalar`() {
        val juevesDeInstalacion = LocalDate.of(2026, 9, 24)
        assertEquals(1, semana(juevesDeInstalacion, LocalDate.of(2026, 9, 27)))
        assertEquals(2, semana(juevesDeInstalacion, LocalDate.of(2026, 9, 28)))
        assertEquals(3, semana(juevesDeInstalacion, LocalDate.of(2026, 10, 5)))
    }

    @Test fun `un reloj que retrocede da semana cero o negativa, no revienta`() {
        // Documenta por que revisarSemana() y PantallaDeAjustes aplican
        // coerceAtLeast(1) al resultado: limitesDe() exige semana >= 1 y esto
        // puede pasar de verdad (cambio de hora, backup restaurado).
        val instalacion = LocalDate.of(2026, 9, 24)
        assertEquals(0, semana(instalacion, LocalDate.of(2026, 9, 17)))
        assertEquals(-1, semana(instalacion, LocalDate.of(2026, 9, 10)))
    }

    @Test fun `la barra por defecto es una sexta parte de la media, entre 10 y 60`() {
        assertEquals(25, limitesConObjetivo(150).barraMin)
        assertEquals(10, limitesConObjetivo(30).barraMin)
        assertEquals(60, limitesConObjetivo(600).barraMin)
    }

    @Test fun `el bloqueo va de un tercio de la media a la media entera`() {
        assertEquals(50..150, rangoDeBloqueo(150))
        // Con poco uso el rango no se queda en nada.
        assertEquals(15..30, rangoDeBloqueo(20))
        assertEquals(100, limitesConObjetivo(150).bloqueoMin)
    }

    @Test fun `lo elegido a mano manda, pero el bloqueo no sale del rango`() {
        val l = limitesConObjetivo(150, barraFija = 20, bloqueoFijo = 60)
        assertEquals(Limites(150, 20, 60), l)
        assertEquals(150, limitesConObjetivo(150, bloqueoFijo = 999).bloqueoMin)
        assertEquals(50, limitesConObjetivo(150, bloqueoFijo = 5).bloqueoMin)
    }

    @Test fun `el calculo automatico da lo mismo que el tope con su objetivo`() {
        val auto = limitesDe(120, 1)
        assertEquals(limitesConObjetivo(auto.objetivoMin), auto)
    }
}
