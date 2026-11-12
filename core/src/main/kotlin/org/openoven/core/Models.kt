package org.openoven.core

import kotlinx.coroutines.flow.StateFlow

const val UNCHANGED = 65535
enum class ConnectionState { DISCONNECTED, LISTENING, AUTHENTICATING, CONNECTED, ERROR }
data class ConnectionInfo(val state: ConnectionState, val message: String = "", val demo: Boolean = false)
data class LanSettings(
    val clientIdentity: String,
    val localAddress: String,
    val tcpPort: Int = 7779,
    val udpPort: Int = 7777,
    val broadcastAddress: String? = null,
    val expectedDeviceAddress: String? = null,
    val timeoutMillis: Int = 4000,
    val demo: Boolean = false
)
data class OvenIdentity(
    val deviceId: Int, val softwareVersion: Int, val hardwareVersion: Int,
    val serialNumber: Long, val panel: Int, val module: Int,
    val configuration: Int, val timeMinutes: Int
) { val legacyModel get() = deviceId == 53274 && softwareVersion < 355 }
data class OvenStatus(
    val power: Int = 0, val program: Int = 0, val wifiControl: Int = 0,
    val temperature: Int = 0, val probeTemperature: Int = 0,
    val elapsedMinutes: Int = 0, val startMinutes: Int = 0,
    val sensorStatus: Int = 0, val aod: Int = 0, val probeStatus: Int = 0,
    val light: Int = 0, val door: Int = 0, val doorLock: Int = 0,
    val water: Int = 0, val catalyst: Int = 0, val rapidHeating: Int = 0,
    val currentStage: Int = 1, val function: Int = 0,
    val targetTemperature: Int = 180, val targetProbeTemperature: Int = 80,
    /** Live status @42: remaining time, not total or elapsed time. Recipe inputs use durationMinutes. */
    val remainingMinutes: Int = 20, val endMinutes: Int = 0,
    val childLock: Int = 0, val shelf: Int = 0, val controlMode: Int = 0,
    val errors: Int = 0, val alarms: Int = 0, val wifiErrors: Int = 0,
    val writeErrors: Int = 0, val steamCounter: Int = 0,
    val controlField: Int = 0, val updateStatus: Int = 0, val updateProgress: Int = 0,
    val observedAtMillis: Long = 0,
    val remainingTimeReliable:Boolean = true
)
data class CookingStage(val function: Int = 1, val temperature: Int = 180, val probeTemperature: Int = UNCHANGED, val durationMinutes: Int = 20)
data class OvenCommand(val address: Int, val payload: ByteArray, val group: Int = 258, val opcode: Int = 1, val responseOpcode: Int = 3)
interface OvenClient {
    val connection: StateFlow<ConnectionInfo>
    val identity: StateFlow<OvenIdentity?>
    val status: StateFlow<OvenStatus?>
    suspend fun connect(settings: LanSettings)
    suspend fun disconnect()
    suspend fun refresh()
    suspend fun send(command: OvenCommand)
}
