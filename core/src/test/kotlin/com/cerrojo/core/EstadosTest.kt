package com.cerrojo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EstadosTest {
    /** Barra de 20 min, bloqueo de 60. */
    private val limites = Limites(objetivoMin = 120, barraMin = 20, bloqueoMin = 60)
    private val hoy = "2026-10-06"

    private fun tics(veces: Int, desde: EstadoApp, ahoraMs: Long = 0L, enPrimerPlano: Boolean = true): EstadoApp {
        var e = desde
        repeat(veces) { i -> e = avanzar(e, Evento.Tick(enPrimerPlano, ahoraMs + i * 1000L, hoy), limites) }
        return e
    }

    @Test fun `usar la app carga la barra`() {
        val e = tics(60, EstadoApp())
        assertEquals(Estado.EN_SESION, e.estado)
        assertEquals(60, e.segBarra)
        assertEquals(0.05f, e.carga(limites), 0.001f)
    }

    @Test fun `fuera de la app la barra ni carga ni baja`() {
        val cargada = tics(5 * 60, EstadoApp())
        val horasFuera = tics(1, cargada, ahoraMs = 3 * 3_600_000L, enPrimerPlano = false)
        assertEquals(5 * 60, horasFuera.segBarra)
        assertEquals(Estado.EN_SESION, tics(1, horasFuera, ahoraMs = 3 * 3_600_000L).estado)
    }

    @Test fun `cuatro ratos de cinco minutos llenan una barra de veinte`() {
        var e = EstadoApp()
        var ahora = 0L
        repeat(4) {
            e = tics(5 * 60, e, ahoraMs = ahora)
            ahora += 5 * 60_000L + 30 * 60_000L
        }
        assertEquals(Estado.ENFRIANDO, e.estado)
    }

    @Test fun `llena bloquea el tiempo elegido y luego la barra vuelve a cero`() {
        val llena = tics(20 * 60, EstadoApp())
        assertEquals(Estado.ENFRIANDO, llena.estado)
        // El bloqueo cuenta desde el tic que la llena (el ultimo, en 1_199_000).
        assertEquals(1_199_000L + 60 * 60_000L, llena.finBloqueoMs)

        val aMitad = tics(1, llena, ahoraMs = llena.finBloqueoMs - 1, enPrimerPlano = true)
        assertEquals(Estado.ENFRIANDO, aMitad.estado)

        val despues = avanzar(llena, Evento.Tick(false, llena.finBloqueoMs, hoy), limites)
        assertEquals(Estado.LIBRE, despues.estado)
        assertEquals(0, despues.segBarra)
    }

    @Test fun `bloqueada no carga ni cuenta`() {
        val llena = tics(20 * 60, EstadoApp())
        val e = tics(60, llena, ahoraMs = 2_000_000L)
        assertEquals(20 * 60, e.segBarra)
        assertEquals(20 * 60, e.segHoy)
    }

    @Test fun `el dia nuevo vacia la barra`() {
        val medio = tics(10 * 60, EstadoApp())
        val manana = avanzar(medio, Evento.Tick(false, 0L, "2026-10-07"), limites)
        assertEquals(Estado.LIBRE, manana.estado)
        assertEquals(0, manana.segBarra)
    }

    @Test fun `un bloqueo que cruza la medianoche se cumple`() {
        val llena = tics(20 * 60, EstadoApp())
        val manana = avanzar(llena, Evento.Tick(true, llena.finBloqueoMs - 1, "2026-10-07"), limites)
        assertEquals(Estado.ENFRIANDO, manana.estado)
        assertEquals(0, manana.segHoy)
    }

    @Test fun `desbloquear da cinco minutos y vuelve a bloquear entero`() {
        val llena = tics(20 * 60, EstadoApp())
        val desbloqueada = avanzar(llena, Evento.Desbloqueo, limites)
        assertEquals(Estado.EN_SESION, desbloqueada.estado)

        val casi = tics(5 * 60 - 1, desbloqueada, ahoraMs = 2_000_000L)
        assertEquals(Estado.EN_SESION, casi.estado)
        val otra = tics(1, casi, ahoraMs = 3_000_000L)
        assertEquals(Estado.ENFRIANDO, otra.estado)
        assertEquals(3_000_000L + 60 * 60_000L, otra.finBloqueoMs)
    }

    @Test fun `el desbloqueo no hace nada si la app no esta bloqueada`() {
        val libre = tics(5, EstadoApp())
        assertEquals(libre, avanzar(libre, Evento.Desbloqueo, limites))
    }

    @Test fun `achicar la barra por debajo de lo cargado bloquea aunque la app no este delante`() {
        val cargada = tics(15 * 60, EstadoApp())
        val e = avanzar(cargada, Evento.Tick(false, 0L, hoy), Limites(120, 10, 60))
        assertEquals(Estado.ENFRIANDO, e.estado)
    }

    @Test fun `un estado de la v1_6 sin presupuesto se libera`() {
        val viejo = EstadoApp(estado = Estado.SIN_PRESUPUESTO, segHoy = 60, dia = hoy)
        assertEquals(Estado.LIBRE, avanzar(viejo, Evento.Tick(false, 0L, hoy), limites).estado)
    }
}
