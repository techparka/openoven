package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

/** Real TCP sockets, bound exclusively to loopback. No vendor/device discovery. */
class LanSocketClientTest {
    private val config = LanSettings("1234567890123456", "127.0.0.1", tcpPort = 0, timeoutMillis = 500, demo = true)

    private fun scenario(options: SimulationOptions = SimulationOptions(), block: suspend (LanSocketClient) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = LanSocketClient(scope, simulationOptions = options)
        try { withTimeout(15000) { block(client) } }
        finally { client.disconnect(); scope.cancel() }
    }

    @Test fun handshakeAndLightRoundtripOnActualEphemeralPort() = scenario {
        it.connect(config)
        assertEquals(ConnectionState.CONNECTED, it.connection.value.state)
        assertEquals(53274, it.identity.value!!.deviceId)
        assertEquals(31, it.status.value!!.temperature)
        it.send(OvenCommands.common(light = 100))
        assertEquals(1, it.status.value!!.light)
        it.send(OvenCommands.common(light = 0))
        assertEquals(0, it.status.value!!.light)
    }

    @Test fun everyStatusFrameSplitBoundaryAndOneByteFragments() = runBlocking {
        // Status response is 11-byte header + 77-byte payload.
        for (boundary in 1 until 88) scenario(SimulationOptions(splitAt = boundary)) {
            it.connect(config)
            assertEquals("split=$boundary", 31, it.status.value!!.temperature)
        }
        scenario(SimulationOptions(fragmentBytes = 1)) {
            it.connect(config.copy(timeoutMillis = 4000)); it.send(OvenCommands.common(light = 100))
            assertEquals(1, it.status.value!!.light)
        }
    }

    @Test fun coalescedStaleAndMatchingFrameAreSeparated() = scenario(SimulationOptions(prependStaleResponseAt = 7)) {
        it.connect(config)
        assertEquals(31, it.status.value!!.temperature)
        it.refresh()
        assertEquals(ConnectionState.CONNECTED, it.connection.value.state)
    }

    @Test fun authenticationAndCorrelationRejectInvalidResponses() = runBlocking {
        for (options in listOf(SimulationOptions(invalidAes = true), SimulationOptions(wrongAddressAt = 6),
            SimulationOptions(badSequenceAt = 6), SimulationOptions(dropAfterRequests = 2))) scenario(options) {
            var failed = false
            try { it.connect(config) } catch (e: Exception) { failed = true }
            assertTrue("options=$options", failed)
            assertEquals(ConnectionState.ERROR, it.connection.value.state)
        }
    }

    @Test fun responseDeadlineRejectsDelay() = scenario(SimulationOptions(responseDelayMillis = 700)) {
        var error: Exception? = null
        try { it.connect(config) } catch (e: Exception) { error = e }
        assertTrue(error is SocketTimeoutException)
        assertEquals(ConnectionState.ERROR, it.connection.value.state)
    }

    @Test fun concurrentRequestsAreSerialized() = scenario {
        it.connect(config)
        coroutineScope { repeat(30) { _ -> launch { it.refresh() } } }
        assertEquals(ConnectionState.CONNECTED, it.connection.value.state)
        it.send(OvenCommands.common(light = 100))
        assertEquals(1, it.status.value!!.light)
    }

    @Test fun failedManualRefreshImmediatelyInvalidatesConnectedState() = scenario(SimulationOptions(dropAfterRequests=7)) { client ->
        client.connect(config)
        assertEquals(ConnectionState.CONNECTED,client.connection.value.state)
        val result=runCatching { client.refresh() }
        assertTrue(result.isFailure)
        assertEquals(ConnectionState.ERROR,client.connection.value.state)
    }

    @Test fun disconnectReconnectCannotReplayQueuedCommandsOrOldFailure() = scenario(SimulationOptions(responseDelayMillis = 35)) { client ->
        client.connect(config)
        supervisorScope {
            val stale = (1..20).map { async { runCatching { client.send(OvenCommands.common(light = 100)) } } }
            delay(10)
            client.disconnect()
            assertNull(client.status.value)
            client.connect(config)
            stale.forEach { it.await() }
            delay(1700) // previous poll has had enough time to wake or fail
            assertEquals(ConnectionState.CONNECTED, client.connection.value.state)
            assertEquals(0, client.status.value!!.light)
            client.refresh()
        }
    }

    @Test fun cancelledConnectCanBeFollowedByAnotherConnection() = scenario(SimulationOptions(responseDelayMillis = 35)) { client ->
        coroutineScope {
            val connecting = launch { client.connect(config) }
            delay(20)
            client.disconnect()
            connecting.cancelAndJoin()
        }
        client.connect(config)
        assertEquals(ConnectionState.CONNECTED, client.connection.value.state)
    }

    @Test fun expectedAddressConstraintAcceptsLoopback() = scenario {
        it.connect(config.copy(expectedDeviceAddress = "127.0.0.1"))
        assertEquals(ConnectionState.CONNECTED, it.connection.value.state)
    }

    @Test fun expectedAddressConstraintRejectsUnexpectedPeer() = scenario {
        var error: Exception? = null
        try { it.connect(config.copy(expectedDeviceAddress = "127.0.0.2")) } catch (e: Exception) { error = e }
        assertTrue(error is SocketTimeoutException)
        assertEquals(ConnectionState.ERROR, it.connection.value.state)
    }

    @Test fun reconnectWhileOldPollIsReadingDoesNotCloseNewSession() = scenario(SimulationOptions(responseDelayMillis = 100)) {
        it.connect(config)
        delay(1540)
        it.disconnect()
        it.connect(config)
        delay(1700)
        assertEquals(ConnectionState.CONNECTED, it.connection.value.state)
        it.send(OvenCommands.common(light = 100))
        assertEquals(1, it.status.value!!.light)
    }
}
