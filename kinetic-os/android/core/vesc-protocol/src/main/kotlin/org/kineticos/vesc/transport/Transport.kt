package org.kineticos.vesc.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TransportKind { BLE, USB, TCP, SIMULATION }

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data class Connected(val description: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

/**
 * Byte pipe to a VESC. Implementations: BLE (Nordic UART), USB CDC-ACM, TCP (VESC Express / VESC Tool bridge),
 * and [org.kineticos.vesc.sim.SimulatedVescTransport]. The UI never talks to a transport directly.
 */
interface VescTransport {
    val kind: TransportKind
    val state: StateFlow<LinkState>
    /** Raw incoming fragments (not packet aligned). */
    val incoming: Flow<ByteArray>
    /** Max bytes per write; larger frames are chunked by [write]. */
    val maxChunk: Int
    suspend fun connect()
    suspend fun write(bytes: ByteArray)
    suspend fun disconnect()
}
