package org.openoven.core

import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Wire transforms with separate handling of negative selectors for ARM and x86. */
enum class TransformAbi { ARM, X86 }

object ByteTransforms {
    fun hash(data: ByteArray, seed: Int, mode: Int, abi: TransformAbi = TransformAbi.ARM) = transform(data, seed, mode, abi, 0)
    fun hash2(data: ByteArray, seed: Int, mode: Int, abi: TransformAbi = TransformAbi.ARM) = transform(data, seed, mode, abi, 1)
    fun wifiEasy(data: ByteArray, seed: Int, mode: Int, abi: TransformAbi = TransformAbi.ARM) = transform(data, seed, mode, abi, 2)

    private fun transform(data: ByteArray, seed: Int, m: Int, abi: TransformAbi, kind: Int): ByteArray {
        var counter = seed.toLong()
        return ByteArray(data.size) { i ->
            val c = data[i].toInt()
            val rem = (counter.toDouble() % 10.0).toInt()
            val k = if (abi == TransformAbi.ARM && rem < 0) 0 else rem
            // Int arithmetic deliberately wraps; branches 2/3/5 of Hash and Hash2 use double.
            val value = when (kind) {
                0 -> when (k) {
                    0 -> (c - 10 * m).toDouble(); 1 -> (c - 15 * m).toDouble()
                    2 -> c.toDouble() + m.toDouble() * (counter.toDouble() % 11.0)
                    3 -> c.toDouble() + m.toDouble() * (counter.toDouble() % 44.0)
                    4 -> (c - 12 * m).toDouble()
                    5 -> c.toDouble() - m.toDouble() * (counter.toDouble() % 12.0)
                    6 -> (c - 9 * m).toDouble(); 7 -> (c - 7 * m).toDouble()
                    8 -> (c + 15 * m).toDouble(); 9 -> (c + 17 * m).toDouble(); else -> 0.0
                }
                1 -> when (k) {
                    0 -> (c - 11 * m).toDouble(); 1 -> (c - 16 * m).toDouble()
                    2 -> c.toDouble() + m.toDouble() * (counter.toDouble() % 12.0)
                    3 -> c.toDouble() + m.toDouble() * (counter.toDouble() % 45.0)
                    4 -> (c - 13 * m).toDouble()
                    5 -> c.toDouble() - m.toDouble() * (counter.toDouble() % 13.0)
                    6 -> (c - 10 * m).toDouble(); 7 -> (c - 8 * m).toDouble()
                    8 -> (c + 16 * m).toDouble(); 9 -> (c + 18 * m).toDouble(); else -> 0.0
                }
                else -> when (k) {
                    0 -> (c - m).toDouble(); 1 -> (c - 15 * m).toDouble()
                    2 -> (c + 2 * m).toDouble(); 3 -> (c + 3 * m).toDouble()
                    4 -> (c - 13 * m).toDouble(); 5 -> (c - 14 * m).toDouble()
                    6 -> (c - 4 * m).toDouble(); 7 -> (c - 11 * m).toDouble()
                    8 -> (c + 9 * m).toDouble(); 9 -> (c + 5 * m).toDouble(); else -> 0.0
                }
            }
            counter = (counter + 1) % if (kind == 1) 4294967296L else 256L
            (value % 256.0).toInt().toByte()
        }
    }
}

data class ProtocolFrame(val sequence: Int, val opcode: Int, val group: Int, val address: Int,
                         val declaredLength: Int, val payload: ByteArray)

private fun ByteArray.word(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
private fun ByteArray.putWord(offset: Int, value: Int) {
    require(value in 0..65535) { "Word outside unsigned 16-bit range" }
    this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte()
}

/** 11-byte little-endian wire header, then optional body; no header CRC. */
object FrameCodec {
    fun encode(frame: ProtocolFrame, key: Int): ByteArray {
        require(frame.opcode in 0..255)
        require(frame.payload.isEmpty() || frame.payload.size == frame.declaredLength) { "Body length mismatch" }
        val bytes = ByteArray(11 + frame.payload.size)
        bytes.putWord(0, frame.sequence); bytes[2] = frame.opcode.toByte(); bytes.putWord(3, frame.group)
        for (i in 0..3) bytes[5 + i] = (frame.address ushr (8 * i)).toByte()
        bytes.putWord(9, frame.declaredLength); frame.payload.copyInto(bytes, 11)
        return ByteTransforms.hash2(bytes, key, 1)
    }
    fun decodeHeader(bytes: ByteArray, key: Int): ProtocolFrame {
        require(bytes.size >= 11) { "Incomplete frame header" }
        return parse(ByteTransforms.hash2(bytes.copyOfRange(0, 11), key, -1), ByteArray(0))
    }
    fun decodeResponse(bytes: ByteArray, key: Int): ProtocolFrame {
        require(bytes.size >= 11) { "Incomplete frame header" }
        val plain = ByteTransforms.hash2(bytes, key, -1)
        val header = parse(plain, plain.copyOfRange(11, plain.size))
        require(header.payload.size == header.declaredLength) { "Incomplete or trailing response body" }
        return header
    }
    private fun parse(bytes: ByteArray, body: ByteArray): ProtocolFrame {
        var address = 0
        for (i in 0..3) address = address or ((bytes[5 + i].toInt() and 255) shl (8 * i))
        return ProtocolFrame(bytes.word(0), bytes[2].toInt() and 255, bytes.word(3), address, bytes.word(9), body)
    }
}

object OvenCommands {
    /** Raw stage action 1 occurs during program selection; power starts via common(100). */
    const val SELECT_PROGRAM = 1
    fun common(power: Int = UNCHANGED, childLock: Int = UNCHANGED, light: Int = UNCHANGED, clearAlarm: Int = 0): OvenCommand {
        require(clearAlarm in 0..16)
        val body = ByteArray(38) { -1 }
        body.putWord(0, power); body.putWord(2, childLock); body.putWord(4, light)
        if (clearAlarm != 0) body.putWord(6, 1 shl (clearAlarm - 1))
        return OvenCommand(228, checksum(body))
    }
    fun stages(stages: List<CookingStage>, action: Int = 0, program: Int = 65534, weight: Int = 65534,
               endMinutes: Int = UNCHANGED, rapidHeating: Int = UNCHANGED, catalyst: Int = UNCHANGED): OvenCommand {
        require(stages.size in 1..3)
        require(action in 0..1) { "Stage action is 0 (edit) or 1 (program selection)" }
        val body = ByteArray(64) { -1 }
        body.putWord(0, program); body.putWord(2, weight); body.putWord(4, action)
        body.putWord(6, endMinutes); body.putWord(8, rapidHeating); body.putWord(10, catalyst)
        body.putWord(12, stages.size)
        stages.forEachIndexed { index, stage ->
            val offset = 14 + index * 10
            body.putWord(offset, if (index == 0 && program in 1..65533) program else stage.function)
            body.putWord(offset + 2, stage.temperature); body.putWord(offset + 4, stage.probeTemperature)
            body.putWord(offset + 6, stage.durationMinutes)
        }
        return OvenCommand(268, checksum(body))
    }
    fun clock(minutes: Int): OvenCommand {
        require(minutes in 0..1439)
        return OvenCommand(124, ByteArray(2).apply { putWord(0, minutes) })
    }
    fun provision(ssid: String, password: String): OvenCommand {
        val body = ByteArray(96)
        utf8Field(ssid, 32).copyInto(body); utf8Field(password, 64).copyInto(body, 32)
        return OvenCommand(200, body, group = 257, opcode = 150, responseOpcode = 147)
    }
    fun apMode(enabled: Boolean) = OvenCommand(124, ByteArray(2) { if (enabled) 0x5a.toByte() else 0xa5.toByte() }, group = 1, opcode = 225, responseOpcode = 227)
    fun checksum(body: ByteArray): ByteArray {
        require(body.size % 2 == 0)
        var sum = 0
        for (i in body.indices step 2) sum = (sum + body.word(i)) and 65535
        return body.copyOf(body.size + 2).apply { putWord(body.size, sum xor 65535) }
    }
    private fun utf8Field(value: String, size: Int): ByteArray {
        require('\u0000' !in value)
        val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= size) { "Wi-Fi field too long in UTF-8 bytes" }
        return bytes.copyOf(size)
    }
}

object HandshakeKeys {
    fun sessionBytes(): ByteArray {
        val random = Random(56L); random.nextInt(100)
        return ByteArray(16) { random.nextInt(255).toByte() }
    }
    fun modemAesKey(modemId: ByteArray): ByteArray {
        require(modemId.size >= 8)
        val material = ByteArray(16) { if (it < 8) modemId[it] else ((it - 8) * 3).toByte() }
        return ByteTransforms.hash2(material, 777, 1)
    }
    fun portAesKey(port: Int): ByteArray {
        require(port in 1..65535)
        val prefix = port.toString().toByteArray(Charsets.US_ASCII)
        val fibonacci = byteArrayOf(0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, -112, -23, 0, 1)
        val material = ByteArray(16)
        prefix.copyInto(material); fibonacci.copyInto(material, prefix.size, 0, 16 - prefix.size)
        return ByteTransforms.hash2(material, 21, 1)
    }
    fun encryptBlock16(block: ByteArray, key: ByteArray): ByteArray {
        require(block.size == 16 && key.size == 16)
        return Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block).copyOf(16)
        }
    }
}

object DiscoveryCodec {
    fun beacon(identity: String, ip: String, tcpPort: Int, counter: Int): ByteArray {
        require(identity.toByteArray(Charsets.UTF_8).size == 16 && '\u0000' !in identity)
        require(ip.length <= 15 && ip.matches(Regex("[0-9.]+")))
        require(tcpPort in 1..65535 && counter in 0..999999)
        return ByteTransforms.hash2(("#SMT_AMC#" + identity + ip.padEnd(15) + tcpPort.toString().padEnd(6) + counter.toString().padEnd(6)).toByteArray(Charsets.UTF_8), 123, 1)
    }
    /** UDP bootstrap uses truncated UTF-16 code units, unlike TCP's UTF-8 fields. */
    fun provision(identity: String, ssid: String, password: String): ByteArray {
        val name = ssid.trim(); val pass = password.trim(); val id = identity.toByteArray(Charsets.UTF_8)
        require(name.length <= 32 && pass.length <= 64 && id.size <= 16)
        val body = ByteArray(121)
        "#SMT_PAR#".toByteArray(Charsets.US_ASCII).copyInto(body)
        name.forEachIndexed { index, c -> body[9 + index] = c.code.toByte() }
        pass.forEachIndexed { index, c -> body[41 + index] = c.code.toByte() }
        id.copyInto(body, 105)
        return ByteTransforms.hash2(body, 123, 1)
    }
}

object StatusCodec {
    fun identity(body: ByteArray): OvenIdentity {
        require(body.size >= 26) { "Oven identity requires 26 bytes" }
        var serial = 0L
        for (i in 0..3) serial = serial or ((body[6 + i].toLong() and 255) shl (8 * i))
        return OvenIdentity(body.word(0), body.word(2), body.word(4), serial,
            body[10].toInt() and 255, body[11].toInt() and 255, body.word(12), body.word(24))
    }
    fun status(body: ByteArray, previous: OvenStatus? = null): OvenStatus {
        require(body.size >= 64) { "Oven status requires 64 bytes" }
        val rapid = body.word(32)
        val remainingTimeReliable=(rapid==0 || rapid==100 || rapid==UNCHANGED) && body.word(42) in 0..360
        val remaining = if (remainingTimeReliable) body.word(42) else previous?.remainingMinutes ?: UNCHANGED
        val program = body.word(2)
        val function = when {
            program == 13 || program == 65520 -> program
            program > 12 -> programFunction(program)
            else -> body.word(36)
        }
        return OvenStatus(power = body.word(0), program = program, wifiControl = body.word(4),
            temperature = body.word(6), probeTemperature = body.word(8), elapsedMinutes = body.word(10),
            startMinutes = body.word(12), sensorStatus = body.word(14), aod = body.word(16), probeStatus = body.word(18),
            light = body.word(20), door = body.word(22), doorLock = body.word(24), water = body[26].toInt() and 255,
            catalyst = body.word(28), rapidHeating = rapid, currentStage = body.word(34) + 1,
            function = function, targetTemperature = body.word(38), targetProbeTemperature = body.word(40),
            remainingMinutes = remaining, remainingTimeReliable=remainingTimeReliable, endMinutes = body.word(44), childLock = body.word(46), shelf = body.word(48),
            controlMode = body.word(50), errors = body.word(54), alarms = body.word(56), wifiErrors = body.word(58),
            writeErrors = body.word(60), steamCounter = body.word(62),
            controlField = if (body.size >= 72) body[69].toInt() and 255 else previous?.controlField ?: 0,
            updateStatus = if (body.size >= 72) body[70].toInt() and 255 else previous?.updateStatus ?: 0,
            updateProgress = if (body.size >= 72) body[71].toInt() and 255 else previous?.updateProgress ?: 0)
    }
    private fun programFunction(program: Int): Int = when (program) {
        in 1..13 -> program
        14,15,16,17,27,28,32,34,35,36,37,38,39,40,41,42,43,44,45,47,52,54,55,56,57,58,59 -> 6
        48 -> 9; 51,53 -> 13; 18,19,20,21,60,61 -> 4; 68,69 -> 7
        22,23,24,25,26,29,30,31,33,46,49,50,62,63,64,65,66,67 -> 1
        else -> -1
    }
}
