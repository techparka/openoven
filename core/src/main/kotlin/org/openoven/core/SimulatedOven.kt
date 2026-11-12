package org.openoven.core

import kotlinx.coroutines.*
import java.io.EOFException
import java.net.Socket

data class SimulationOptions(val fragmentBytes: Int = Int.MAX_VALUE, val responseDelayMillis: Long = 0,
                             val dropAfterRequests: Int = Int.MAX_VALUE, val badSequenceAt: Int = -1,
                             val invalidAes: Boolean = false, val splitAt: Int? = null,
                             val prependStaleResponseAt: Int = -1, val wrongAddressAt: Int = -1,
                             val provisioningOnly: Boolean = false,
                             val provisioningAckLength: Int = 0, val provisioningAckHeaderOnly: Boolean = true,
                             val provisioningAckOpcode: Int = 147, val provisioningAckSequenceOffset: Int = 0,
                             val provisioningAckDelayMillis: Long = 0,
                             val initialStatus:OvenStatus?=null, val minuteMillis:Long=0, val deviceId:Int=53274, val clockNanos:()->Long=System::nanoTime)

/** Explicit test appliance only. No discovery or external network activity. */
class SimulatedOven(private val scope: CoroutineScope, private val options: SimulationOptions = SimulationOptions()) {
    private var job: Job? = null
    private var socket: Socket? = null
    private val modem = byteArrayOf(16, 32, 48, 64, 80, 96, 112, 120)
    private val challenge = ByteArray(16) { (it * 7 + 2).toByte() }
    private var key = 0
    private var authenticated = false
    private var state = options.initialStatus ?: OvenStatus(temperature = 31, targetTemperature = 180, targetProbeTemperature = 80, sensorStatus = 16, wifiControl = 7)
    private var portKey = 7779
    private var tickAtNanos=options.clockNanos()

    fun connect(ip: String, port: Int, authenticationPort: Int = 7779) {
        require(java.net.InetAddress.getByName(ip).isLoopbackAddress) { "Simulator only supports loopback" }
        close()
        key = 0
        authenticated = false
        portKey = authenticationPort
        job = scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket(ip, port).apply { soTimeout = 30000; tcpNoDelay = true }
                socket = sock
                var count = 0
                while (isActive) {
                    val header = readExactly(sock, 11)
                    val meta = FrameCodec.decodeHeader(header, key)
                    require(meta.declaredLength in 0..4096)
                    val payloadLength = if (meta.opcode == 0 && !(meta.address == 144 && meta.group == 1)) 0 else meta.declaredLength
                    val encodedBody = readExactly(sock, payloadLength)
                    val decoded = ByteTransforms.hash2(header + encodedBody, key, -1)
                    val body = decoded.copyOfRange(11, decoded.size)
                    if (options.provisioningOnly && authenticated && meta.group == 258)
                        throw java.io.IOException("AP simulator rejects normal model/status/control requests")
                    val provisioning = authenticated && meta.opcode == 150 && meta.group == 257 && meta.address == 200
                    if (provisioning) require(body.size == 96) { "Wi-Fi fields must contain 96 bytes" }
                    if (++count > options.dropAfterRequests) { sock.close(); break }
                    val responseKey = key
                    var nextKey = key
                    val responseOpcode = if (provisioning) options.provisioningAckOpcode
                        else when (meta.opcode) { 0 -> 2; 150 -> 147; 225 -> 227; else -> 3 }
                    if(state.power>0 && options.minuteMillis>0) {
                        val now=options.clockNanos()
                        val elapsed=(now-tickAtNanos)/1_000_000/options.minuteMillis
                        if(elapsed>0) {
                            val minutes=elapsed.coerceAtMost(360).toInt()
                            state=state.copy(elapsedMinutes=(state.elapsedMinutes+minutes).coerceAtMost(65534),
                                remainingMinutes=(state.remainingMinutes-minutes).coerceAtLeast(0))
                            tickAtNanos+=elapsed*options.minuteMillis*1_000_000
                        }
                    } else tickAtNanos=options.clockNanos()
                    val response: ByteArray = when {
                        provisioning -> ByteArray(if (options.provisioningAckHeaderOnly) 0 else options.provisioningAckLength)
                        meta.group == 1 && meta.address == 92 -> modem + ByteArray(4)
                        meta.group == 1 && meta.address == 128 && meta.opcode == 1 -> {
                            nextKey = body[0].toInt(); ByteArray(16)
                        }
                        meta.group == 1 && meta.address == 144 && meta.opcode == 0 -> {
                            HandshakeKeys.encryptBlock16(body, HandshakeKeys.modemAesKey(modem)).also { if (options.invalidAes) it[0] = (it[0].toInt() xor 1).toByte() }
                        }
                        meta.group == 1 && meta.address == 128 && meta.opcode == 0 -> challenge
                        meta.group == 1 && meta.address == 144 && meta.opcode == 1 -> {
                            check(body.contentEquals(HandshakeKeys.encryptBlock16(challenge, HandshakeKeys.portAesKey(portKey))))
                            authenticated = true; ByteArray(16)
                        }
                        authenticated && meta.address == 100 -> identityBody()
                        authenticated && meta.opcode == 0 && meta.address == 128 -> statusBody()
                        authenticated && meta.opcode == 1 && meta.address == 228 -> {
                            checkChecksum(body)
                            val power = word(body, 0); val lock = word(body, 2); val light = word(body, 4)
                            state = state.copy(power = if (power == UNCHANGED) state.power else if (power == 100) 1 else 0,
                                childLock = if (lock == UNCHANGED) state.childLock else if (lock == 100) 1 else 0,
                                light = if (light == UNCHANGED) state.light else if (light == 100) 1 else 0,
                                alarms = if (word(body, 6) != UNCHANGED) 0 else state.alarms)
                            ByteArray(40)
                        }
                        authenticated && meta.opcode == 1 && meta.address == 268 -> {
                            checkChecksum(body)
                            val offset=if(word(body,4)==1 && word(body,0)==65534)(state.currentStage-1)*10 else 0
                            state = state.copy(function = word(body, 14+offset).takeIf { it != UNCHANGED } ?: state.function,
                                targetTemperature = word(body, 16+offset).takeIf { it != UNCHANGED } ?: state.targetTemperature,
                                targetProbeTemperature = word(body, 18+offset).takeIf { it != UNCHANGED } ?: state.targetProbeTemperature,
                                remainingMinutes = word(body, 20+offset).takeIf { it != UNCHANGED } ?: state.remainingMinutes,
                                endMinutes = word(body, 6).takeIf { it != UNCHANGED } ?: state.endMinutes,
                                rapidHeating = word(body, 8).takeIf { it != UNCHANGED } ?: state.rapidHeating,
                                catalyst = word(body, 10).takeIf { it != UNCHANGED } ?: state.catalyst)
                            ByteArray(66)
                        }
                        else -> ByteArray(meta.declaredLength)
                    }
                    if (options.responseDelayMillis > 0) delay(options.responseDelayMillis)
                    if (provisioning && options.provisioningAckDelayMillis > 0) delay(options.provisioningAckDelayMillis)
                    val seq = if (provisioning) meta.sequence + options.provisioningAckSequenceOffset
                        else if (count == options.badSequenceAt) meta.sequence + 1 else meta.sequence
                    val frame = ProtocolFrame(seq, responseOpcode, meta.group,
                        if (count == options.wrongAddressAt) meta.address + 1 else meta.address,
                        if (provisioning) options.provisioningAckLength else response.size, response)
                    val wire = (if (count == options.prependStaleResponseAt)
                        FrameCodec.encode(frame.copy(sequence = (meta.sequence + 100) and 65535), responseKey) else byteArrayOf()) + FrameCodec.encode(frame, responseKey)
                    if (options.splitAt != null && options.splitAt in 1 until wire.size) {
                        sock.getOutputStream().apply { write(wire, 0, options.splitAt); flush() }
                        delay(3)
                        sock.getOutputStream().apply { write(wire, options.splitAt, wire.size - options.splitAt); flush() }
                        key = nextKey
                        continue
                    }
                    val width = options.fragmentBytes.coerceAtLeast(1)
                    var cursor = 0
                    while (cursor < wire.size) {
                        val size = minOf(width, wire.size - cursor)
                        sock.getOutputStream().apply { write(wire, cursor, size); flush() }
                        cursor += size
                        if (width < wire.size) delay(1)
                    }
                    key = nextKey
                }
            } catch (_: Exception) { runCatching { socket?.close() } }
        }
    }

    private fun checkChecksum(body: ByteArray) {
        require(body.size % 2 == 0)
        var sum = 0
        for (offset in body.indices step 2) sum = (sum + word(body, offset)) and 65535
        require(sum == 65535) { "Nieprawidłowa suma polecenia" }
    }
    private fun identityBody() = ByteArray(26).also {
        put(it, 0, options.deviceId); put(it, 2, 400); put(it, 4, 1); put(it, 6, 1234)
        it[10] = 1; it[11] = 1; put(it, 12, 1); put(it, 24, 600)
    }
    private fun statusBody() = ByteArray(77).also {
        put(it,0,state.power); put(it,2,state.function); put(it,4,state.wifiControl)
        put(it,6,state.temperature); put(it,8,state.probeTemperature); put(it,14,state.sensorStatus)
        put(it,10,state.elapsedMinutes);put(it,12,state.startMinutes);put(it,50,state.controlMode)
        put(it,20,state.light); put(it,22,state.door); put(it,24,state.doorLock); it[26] = state.water.toByte()
        put(it,28,state.catalyst); put(it,32,state.rapidHeating); put(it,34,state.currentStage-1)
        put(it,36,state.function); put(it,38,state.targetTemperature); put(it,40,state.targetProbeTemperature)
        put(it,42,state.remainingMinutes); put(it,44,state.endMinutes); put(it,46,state.childLock)
        put(it,54,state.errors); put(it,56,state.alarms)
    }
    private fun word(body: ByteArray, at: Int) = (body[at].toInt() and 255) or ((body[at+1].toInt() and 255) shl 8)
    private fun put(body: ByteArray, at: Int, value: Int) { body[at] = value.toByte(); body[at+1] = (value ushr 8).toByte() }
    private fun readExactly(sock: Socket, count: Int): ByteArray {
        val bytes = ByteArray(count); var offset = 0
        while (offset < count) { val n = sock.getInputStream().read(bytes,offset,count-offset); if (n < 0) throw EOFException(); offset += n }
        return bytes
    }
    fun close() { runCatching { socket?.close() }; socket = null; job?.cancel(); job = null }
}
