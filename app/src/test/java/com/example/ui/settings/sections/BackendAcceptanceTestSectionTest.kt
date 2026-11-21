package com.example.ui.settings.sections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendAcceptanceTestSectionTest {
    @Test
    fun enablesD0WhenAModelIsLoadedAndExecutionIsIdle() {
        assertNull(backendAcceptanceBlockReason(true, false, false, false, false))
    }

    @Test
    fun explainsEachExecutionLockThatDisablesD0() {
        assertEquals("Ein anderer Benchmark läuft noch. D0 startet danach.", backendAcceptanceBlockReason(true, true, false, false, false))
        assertEquals("Eine Inferenz läuft noch. D0 startet nach deren Abschluss.", backendAcceptanceBlockReason(true, false, false, true, false))
        assertEquals("Ein Modellimport läuft noch. D0 startet nach dessen Abschluss.", backendAcceptanceBlockReason(true, false, false, false, true))
        assertEquals("Zuerst ein GGUF-Modell laden.", backendAcceptanceBlockReason(false, false, false, false, false))
    }

    @Test
    fun identifiesAnAlreadyRunningD0() {
        assertEquals("Der D0-Test läuft bereits.", backendAcceptanceBlockReason(true, true, true, false, false))
    }
}
