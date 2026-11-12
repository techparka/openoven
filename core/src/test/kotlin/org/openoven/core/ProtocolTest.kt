package org.openoven.core

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun nativeBranchVectorsAndLongCounter() {
        // Independent direct evaluation of each recovered switch branch for zero input.
        assertArrayEquals(hex("f5f00203f3fbf6f81012"), ByteTransforms.hash2(ByteArray(10), 0, 1))
        assertArrayEquals(hex("f6f10203f4fbf7f90f11"), ByteTransforms.hash(ByteArray(10), 0, 1))
        assertArrayEquals(hex("fff10203f3f2fcf50905"), ByteTransforms.wifiEasy(ByteArray(10), 0, 1))
        assertArrayEquals(hex("f81012"), ByteTransforms.hash2(ByteArray(3), Int.MAX_VALUE, 1))
        assertArrayEquals(hex("f500"), ByteTransforms.hash2(ByteArray(2), -1, 1, TransformAbi.ARM).let { byteArrayOf(it[0], (it[1] + 11).toByte()) })
        assertEquals(0, ByteTransforms.hash2(byteArrayOf(80), -1, 1, TransformAbi.X86)[0].toInt())
        assertEquals(69, ByteTransforms.hash2(byteArrayOf(80), -1, 1, TransformAbi.ARM)[0].toInt())
    }
    @Test fun everyByteRoundTripsAcrossCounterBoundaries() {
        val data = ByteArray(768) { it.toByte() }
        for (seed in listOf(0, 123, 238, 255, 777, Int.MAX_VALUE)) {
            for (abi in TransformAbi.entries) {
                assertArrayEquals(data, ByteTransforms.hash2(ByteTransforms.hash2(data, seed, 1, abi), seed, -1, abi))
                assertArrayEquals(data, ByteTransforms.hash(ByteTransforms.hash(data, seed, 1, abi), seed, -1, abi))
                assertArrayEquals(data, ByteTransforms.wifiEasy(ByteTransforms.wifiEasy(data, seed, 1, abi), seed, -1, abi))
            }
        }
    }
    @Test fun readHeaderDeclaresBodyWithoutSendingIt() {
        val read = ProtocolFrame(65000, 0, 258, 228, 74, byteArrayOf())
        val wire = FrameCodec.encode(read, 88)
        assertEquals(11, wire.size)
        val header = FrameCodec.decodeHeader(wire, 88)
        assertEquals(65000, header.sequence); assertEquals(228, header.address)
        assertEquals(74, header.declaredLength)
    }
    @Test fun frameParsingIsUnsignedAndRejectsTruncation() {
        val body = hex("00ff80fe")
        val expected = ProtocolFrame(65535, 2, 258, 0x89abcdef.toInt(), 4, body)
        val wire = FrameCodec.encode(expected, 238)
        val parsed = FrameCodec.decodeResponse(wire, 238)
        assertEquals(expected.address, parsed.address); assertArrayEquals(body, parsed.payload)
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.decodeResponse(wire.copyOf(wire.size - 1), 238) }
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.decodeResponse(wire + 0.toByte(), 238) }
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.decodeHeader(ByteArray(10), 0) }
    }
    @Test fun commandChecksumAndStageLayout() {
        val common = OvenCommands.common(light = 100, clearAlarm = 16)
        assertEquals(228, common.address); assertEquals(40, common.payload.size)
        assertEquals(100, word(common.payload, 4)); assertEquals(32768, word(common.payload, 6))
        assertEquals(65535, sumWords(common.payload))
        val stages = OvenCommands.stages(listOf(CookingStage(5, 210, 80, 42), CookingStage(2, 160, UNCHANGED, 10)), action = 1)
        assertEquals(66, stages.payload.size); assertEquals(2, word(stages.payload, 12))
        assertEquals(1, word(stages.payload, 4)); assertEquals(210, word(stages.payload, 16))
        assertEquals(2, word(stages.payload, 24)); assertEquals(10, word(stages.payload, 30))
        assertEquals(65535, word(stages.payload, 34)); assertEquals(65535, sumWords(stages.payload))
        assertThrows(IllegalArgumentException::class.java) { OvenCommands.stages(emptyList()) }
    }
    @Test fun canonicalAesAndSession() {
        assertArrayEquals(hex("66e94bd4ef8a2c3b884cfa59ca342b2e"), HandshakeKeys.encryptBlock16(ByteArray(16), ByteArray(16)))
        assertEquals(88, HandshakeKeys.sessionBytes()[0].toInt() and 255)
        assertArrayEquals(HandshakeKeys.sessionBytes(), HandshakeKeys.sessionBytes())
        assertThrows(IllegalArgumentException::class.java) { HandshakeKeys.modemAesKey(ByteArray(7)) }
    }
    @Test fun provisioningDoesNotTruncateUtf8() {
        assertEquals(96, OvenCommands.provision("Test", "password").payload.size)
        assertEquals(150, OvenCommands.provision("Test", "password").opcode)
        assertThrows(IllegalArgumentException::class.java) { OvenCommands.provision("ą".repeat(17), "") }
        val datagram = DiscoveryCodec.provision("1234567890123456", " é ", " p ")
        val plain = ByteTransforms.hash2(datagram, 123, -1)
        assertEquals(121, plain.size); assertEquals(233, plain[9].toInt() and 255)
        assertEquals(112, plain[41].toInt()); assertEquals(49, plain[105].toInt())
    }
    @Test fun identityAndStatusBoundsAndRetainedDuration() {
        val id = ByteArray(26); id[6] = -1; id[7] = -1; id[8] = -1; id[9] = -1
        assertEquals(4294967295L, StatusCodec.identity(id).serialNumber)
        val status = ByteArray(74); put(status, 2, 18); put(status, 32, 1); put(status, 34, 2)
        status[26] = -1; status[69] = -1; status[70] = -128; status[71] = 100
        val parsed = StatusCodec.status(status, OvenStatus(remainingMinutes = 45))
        assertEquals(45, parsed.remainingMinutes); assertEquals(4, parsed.function)
        assertEquals(3, parsed.currentStage); assertEquals(255, parsed.water); assertEquals(128, parsed.updateStatus)
        assertThrows(IllegalArgumentException::class.java) { StatusCodec.status(ByteArray(63)) }
    }
    private fun word(bytes: ByteArray, i: Int) = (bytes[i].toInt() and 255) + ((bytes[i+1].toInt() and 255) shl 8)
    private fun sumWords(bytes: ByteArray) = bytes.indices.step(2).sumOf { word(bytes,it) } and 65535
    private fun put(bytes: ByteArray, i: Int, value: Int) { bytes[i] = value.toByte(); bytes[i+1] = (value ushr 8).toByte() }
}
