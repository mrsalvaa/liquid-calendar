package dev.liquidcalendar.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncIntervalTest {
    @Test
    fun `las opciones van de 5 minutos a 1 hora`() {
        assertEquals(listOf(5, 15, 30, 60), SyncInterval.OPTIONS)
    }

    @Test
    fun `las opciones quedan como estan`() {
        SyncInterval.OPTIONS.forEach { assertEquals(it, SyncInterval.normalize(it)) }
    }

    @Test
    fun `solo a mano pasa al valor por defecto`() {
        assertEquals(60, SyncInterval.normalize(0))
    }

    @Test
    fun `lo que pasaba de una hora baja al maximo`() {
        listOf(180, 360, 720).forEach { assertEquals(60, SyncInterval.normalize(it)) }
    }

    @Test
    fun `un valor intermedio sube a la opcion siguiente`() {
        assertEquals(15, SyncInterval.normalize(10))
    }

    @Test
    fun `el trabajo periodico nunca baja del minimo de Android`() {
        assertEquals(15, SyncInterval.periodicMinutes(5))
        assertEquals(15, SyncInterval.periodicMinutes(15))
        assertEquals(60, SyncInterval.periodicMinutes(60))
    }

    @Test
    fun `solo 5 minutos necesita encadenar turnos`() {
        assertTrue(SyncInterval.needsChain(5))
        assertFalse(SyncInterval.needsChain(15))
        assertFalse(SyncInterval.needsChain(0))
    }
}
