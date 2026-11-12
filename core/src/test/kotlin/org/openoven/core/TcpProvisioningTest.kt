package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Configuration-only appliance tests run on actual loopback TCP, never a physical appliance. */
class TcpProvisioningTest {
    private val config = LanSettings("1234567890123456", "127.0.0.1", tcpPort = 0, timeoutMillis = 500, demo = true)

    private fun scenario(options: SimulationOptions, block: suspend (LanSocketClient, List<String>) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val messages = CopyOnWriteArrayList<String>()
        val client = LanSocketClient(scope, messages::add, options)
        try { withTimeout(10000) { block(client, messages) } }
        finally { client.disconnect(); scope.cancel() }
    }

    @Test fun normalConnectionFailsAgainstConfigurationOnlyAp() = scenario(SimulationOptions(provisioningOnly = true)) { client, _ ->
        var failure: Exception? = null
        try { client.connect(config) } catch (e: Exception) { failure = e }
        assertNotNull("The AP rejects model/status requests before configuration", failure)
        assertEquals(ConnectionState.ERROR, client.connection.value.state)
    }

    @Test fun zeroLengthHeaderOnlyAckWorksWithoutStatus() = configurationAck(0, true)
    @Test fun declared96HeaderOnlyAckDoesNotWaitForBody() = configurationAck(96, true)
    @Test fun echoed96ByteAckWorks() = configurationAck(96, false)

    private fun configurationAck(length: Int, headerOnly: Boolean) = scenario(SimulationOptions(
        provisioningOnly = true, provisioningAckLength = length, provisioningAckHeaderOnly = headerOnly,
        fragmentBytes = 1
    )) { client, messages ->
        client.provisionTcp(config, "test-network", "private-test-password")
        assertEquals(ConnectionState.DISCONNECTED, client.connection.value.state)
        assertNull(client.identity.value)
        assertNull(client.status.value)
        assertTrue(messages.any { it.contains("potwierdzono konfigurację") })
        assertFalse(messages.any { it.contains("test-network") || it.contains("private-test-password") ||
            it.contains(config.clientIdentity) || it.contains(config.localAddress) })
    }

    @Test fun wrongAckOpcodeIsRejected() = rejectedAck(SimulationOptions(provisioningOnly = true, provisioningAckOpcode = 3))
    @Test fun wrongAckSequenceIsRejected() = rejectedAck(SimulationOptions(provisioningOnly = true, provisioningAckSequenceOffset = 1))

    private fun rejectedAck(options: SimulationOptions) = scenario(options) { client, messages ->
        var failure: Exception? = null
        try { client.provisionTcp(config, "test-network", "test-password") } catch (e: Exception) { failure = e }
        assertTrue(failure is IOException)
        assertEquals(ConnectionState.ERROR, client.connection.value.state)
        assertFalse(messages.any { it.contains("potwierdzono konfigurację") })
        assertNull(client.status.value)
    }

    @Test fun canceledProvisioningCannotCloseReconnectedSession() = scenario(SimulationOptions(
        provisioningAckDelayMillis = 1500
    )) { client, messages ->
        supervisorScope {
            val old = async { runCatching { client.provisionTcp(config, "test-network", "test-password") } }
            while (messages.none { it.contains("wysyłanie konfiguracji") }) delay(5)
            delay(30) // terminal ACK read is blocked
            client.disconnect()
            old.cancel()
            client.connect(config)
            old.join()
            delay(1700) // old simulator/reader had time to fail or wake
            assertEquals(ConnectionState.CONNECTED, client.connection.value.state)
            assertEquals(31, client.status.value!!.temperature)
            client.refresh()
        }
    }
}
