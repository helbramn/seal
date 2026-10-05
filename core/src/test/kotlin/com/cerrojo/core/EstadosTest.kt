package com.cerrojo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EstadosTest {
    private val limites = Limites(objetivoMin = 20, presupuestoMin = 20)
    private val hoy = "2026-10-05"

    private fun tics(veces: Int, desde: EstadoApp, ahoraMs: Long = 0L, enPrimerPlano: Boolean = true): EstadoApp {
        var e = desde
        repeat(veces) { i -> e = avanzar(e, Evento.Tick(enPrimerPlano, ahoraMs + i * 1000L, hoy), limites) }
        return e
    }

    @Test fun `usar la app cuenta para el dia`() {
        val e = tics(1, EstadoApp())
        assertEquals(Estado.EN_SESION, e.estado)
        assertEquals(1, e.segHoy)
    }

    @Test fun `fuera de primer plano no corre el reloj`() {
        val e = tics(30, EstadoApp(), enPrimerPlano = false)
        assertEquals(Estado.LIBRE, e.estado)
        assertEquals(0, e.segHoy)
    }

    @Test fun `cuatro ratos de cinco minutos con pausas largas agotan un tope de veinte`() {
        // Lo que paso el 4-oct: ratos sueltos con pausas de mas de 5 min no
        // bloqueaban nunca. Ahora todo suma.
        var e = EstadoApp()
        var ahora = 0L
        repeat(4) {
            e = tics(5 * 60, e, ahoraMs = ahora)
            ahora += 5 * 60_000L
            e = tics(1, e, ahoraMs = ahora + 30 * 60_000L, enPrimerPlano = false)
            ahora += 30 * 60_000L
        }
        assertEquals(Estado.SIN_PRESUPUESTO, e.estado)
        assertEquals(20 * 60, e.segHoy)
    }

    @Test fun `bloqueada no se suelta sola por mucho tiempo que pase`() {
        val agotado = tics(20 * 60, EstadoApp())
        val horasDespues = tics(1, agotado, ahoraMs = 5 * 3_600_000L, enPrimerPlano = true)
        assertEquals(Estado.SIN_PRESUPUESTO, horasDespues.estado)
        assertEquals(20 * 60, horasDespues.segHoy)
    }

    @Test fun `el dia siguiente vuelve a estar libre`() {
        val agotado = tics(20 * 60, EstadoApp())
        val manana = avanzar(agotado, Evento.Tick(false, 0L, "2026-10-06"), limites)
        assertEquals(Estado.LIBRE, manana.estado)
        assertEquals(0, manana.segHoy)
    }

    @Test fun `el desbloqueo con friccion da cinco minutos y vuelve a bloquear`() {
        val agotado = tics(20 * 60, EstadoApp())
        val desbloqueado = avanzar(agotado, Evento.Desbloqueo, limites)
        assertEquals(Estado.EN_SESION, desbloqueado.estado)

        val casi = tics(5 * 60 - 1, desbloqueado, ahoraMs = 1_000L)
        assertEquals(Estado.EN_SESION, casi.estado)
        assertEquals(Estado.SIN_PRESUPUESTO, tics(1, casi, ahoraMs = 400_000L).estado)
    }

    @Test fun `tras desbloquear, dejar la app un rato no devuelve tiempo`() {
        val desbloqueado = avanzar(tics(20 * 60, EstadoApp()), Evento.Desbloqueo, limites)
        val fuera = tics(1, desbloqueado, ahoraMs = 60 * 60_000L, enPrimerPlano = false)
        val e = tics(5 * 60, fuera, ahoraMs = 61 * 60_000L)
        assertEquals(Estado.SIN_PRESUPUESTO, e.estado)
    }

    @Test fun `el desbloqueo no hace nada si la app no esta bloqueada`() {
        val libre = tics(5, EstadoApp())
        assertEquals(libre, avanzar(libre, Evento.Desbloqueo, limites))
    }

    @Test fun `un estado de descanso de una version anterior se libera`() {
        val viejo = EstadoApp(estado = Estado.ENFRIANDO, segHoy = 60, dia = hoy)
        assertEquals(Estado.LIBRE, avanzar(viejo, Evento.Tick(false, 0L, hoy), limites).estado)
    }

    @Test fun `bajar el tope por debajo de lo usado bloquea aunque la app no este delante`() {
        val usada = tics(15 * 60, EstadoApp())
        val e = avanzar(usada, Evento.Tick(false, 0L, hoy), Limites(10, 10))
        assertEquals(Estado.SIN_PRESUPUESTO, e.estado)
    }
}
