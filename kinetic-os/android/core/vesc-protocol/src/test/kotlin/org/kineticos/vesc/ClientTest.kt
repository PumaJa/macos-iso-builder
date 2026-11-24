package org.kineticos.vesc

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.kineticos.vesc.client.*
import org.kineticos.vesc.sim.SimulatedVescTransport
import kotlin.test.*

class ClientTest {
    private fun withSim(sim: SimulatedVescTransport = SimulatedVescTransport(), block: suspend (VescClient, SimulatedVescTransport) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            sim.connect()
            val c = VescClient(sim, scope, defaultTimeoutMs = 300)
            c.start(); delay(50)
            try { block(c, sim) } finally { scope.cancel() }
        }

    @Test fun readsFirmwareAndValuesThroughFragmentedLink() = withSim { c, _ ->
        val fw = c.firmware()
        assertEquals("SIM_VESC", fw.hardwareName)
        val v = c.values()
        assertTrue(v.inputVoltageV > 60)
        assertNotNull(v.controllerId)
    }

    @Test fun canForwardingAndPing() = withSim(SimulatedVescTransport(canIds = listOf(1, 2))) { c, _ ->
        assertEquals(listOf(1, 2), c.pingCan())
        assertEquals(2, c.values(canId = 2).controllerId)
    }

    @Test fun retriesOnDroppedReply() = withSim(SimulatedVescTransport(dropEveryNth = 2)) { c, _ ->
        repeat(6) { c.values() }
        assertTrue(c.stats.value.retries >= 1)
    }

    @Test fun crcCorruptionIsDetectedAndRetried() = withSim(SimulatedVescTransport(corruptEveryNth = 3)) { c, _ ->
        repeat(6) { c.values() }
        assertTrue(c.stats.value.crcErrors >= 1)
    }

    @Test fun timeoutWhenNoReply() = withSim(SimulatedVescTransport(dropEveryNth = 1)) { c, _ ->
        assertFailsWith<VescTimeoutException> { c.values() }
    }

    @Test fun writeTokenIsSingleUse() = withSim { c, sim ->
        val t = WriteToken.issue("test", "unit")
        c.writeAckedChecked(org.kineticos.vesc.codec.Commands.setMcConfTempSetup(0.0, 0.5, -5.0, 20.0, -0.9, 0.9, -1000.0, 4000.0, -10.0, 40.0), t)
        assertEquals(1, sim.writesReceived.size)
        assertFailsWith<IllegalStateException> { c.writeAckedChecked(org.kineticos.vesc.codec.Commands.alive(), t) }
    }
}
