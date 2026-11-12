package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FirstConnectionSetupTest {
    private class Link:FirstConnectionPort {
        val ids=mutableListOf<String>()
        var sends=0;var opens=0;var releases=0;var reads=0;var pending=false
        var sendFailure=false;var readFailure=false;var stale=false;var gate:CompletableDeferred<Unit>?=null
        val id="f123NewLocalID56"
        override suspend fun openAccessPoint(clientIdentity:String) { opens++;ids+=clientIdentity;gate?.await() }
        override suspend fun sendWifi(clientIdentity:String,credentials:WifiSetupCredentials,method:SetupMethod) {
            sends++;ids+=clientIdentity
            if(sendFailure) error("private-test-password")
        }
        override suspend fun releaseAccessPoint() { releases++ }
        override suspend fun readHomeOven(clientIdentity:String):Pair<OvenIdentity,OvenStatus> {
            reads++;ids+=clientIdentity
            if(readFailure) error("private-test-network")
            return OvenIdentity(9137,362,259,0,18,2,42,0) to OvenStatus(observedAtMillis=if(stale)1 else System.currentTimeMillis())
        }
        override fun rememberAwaitingHome(value:Boolean) { pending=value }
    }
    private val credentials=WifiSetupCredentials("private-test-network","private-test-password")
    @Test fun freshLocalIdentitySurvivesApTcpAndVerifiedLanWithoutAnyOriginalAppData()=runBlocking {
        val link=Link();val setup=FirstConnectionSetup({link.id},link)
        setup.connectAccessPoint();assertEquals(SetupStep.AP_READY,setup.progress.value.step)
        setup.configure(credentials,SetupMethod.TCP)
        assertEquals(SetupStep.HOME_REQUIRED,setup.progress.value.step)
        assertTrue(setup.progress.value.acknowledged);assertTrue(link.pending)
        assertEquals(0,link.reads) // ACK is not successful onboarding.
        setup.verifyHome()
        assertEquals(SetupStep.COMPLETE,setup.progress.value.step)
        assertFalse(link.pending);assertEquals(1,link.sends)
        assertEquals(listOf(link.id,link.id,link.id),link.ids)
    }
    @Test fun existingHomeWifiPairingDoesNotConfigureOrVisitAccessPoint()=runBlocking {
        val link=Link();val setup=FirstConnectionSetup({link.id},link)
        setup.verifyHome()
        assertEquals(SetupStep.COMPLETE,setup.progress.value.step)
        assertEquals(0,link.opens);assertEquals(0,link.sends);assertEquals(1,link.reads)
    }
    @Test fun awpsExplicitlyHasNoAckAndFailedLanIsNotSuccess()=runBlocking {
        val link=Link();val setup=FirstConnectionSetup({link.id},link)
        setup.connectAccessPoint();setup.configure(credentials,SetupMethod.AWPS)
        assertFalse(setup.progress.value.acknowledged)
        link.readFailure=true;setup.verifyHome()
        assertEquals(SetupStep.HOME_REQUIRED,setup.progress.value.step)
        assertTrue(link.pending);assertEquals(1,link.sends)
        assertFalse(setup.progress.value.toString().contains(credentials.ssid))
    }
    @Test fun lostAckCanResumeAfterRestartWithoutReplayingCredentials()=runBlocking {
        val link=Link();link.sendFailure=true
        val first=FirstConnectionSetup({link.id},link)
        first.connectAccessPoint();first.configure(credentials,SetupMethod.TCP)
        assertEquals(SetupStep.HOME_REQUIRED,first.progress.value.step)
        assertFalse(first.progress.value.acknowledged)
        assertFalse(first.progress.value.toString().contains(credentials.password))
        first.cancel()
        assertEquals(SetupStep.HOME_REQUIRED,first.progress.value.step)
        val restarted=FirstConnectionSetup({link.id},link,link.pending)
        assertEquals(SetupStep.HOME_REQUIRED,restarted.progress.value.step)
        restarted.verifyHome()
        assertEquals(SetupStep.COMPLETE,restarted.progress.value.step)
        assertEquals(1,link.sends)
    }
    @Test fun staleStatusCannotCompleteOnboarding()=runBlocking {
        val link=Link();link.stale=true
        val setup=FirstConnectionSetup({link.id},link)
        setup.verifyHome();assertEquals(SetupStep.HOME_REQUIRED,setup.progress.value.step)
        link.stale=false;setup.verifyHome();assertEquals(SetupStep.COMPLETE,setup.progress.value.step)
    }
    @Test fun invalidCredentialsDoNotChangeApStateAndSecretsAreRedacted()=runBlocking {
        val link=Link();val setup=FirstConnectionSetup({link.id},link)
        setup.connectAccessPoint()
        try { setup.configure(WifiSetupCredentials("SmartIN","test-password"),SetupMethod.TCP);fail("Expected validation") } catch(_:IllegalArgumentException) { }
        assertEquals(SetupStep.AP_READY,setup.progress.value.step);assertEquals(0,link.sends)
        assertFalse(credentials.toString().contains(credentials.password))
        assertThrows(IllegalArgumentException::class.java) { WifiSetupCredentials("dom"," password ").validate(SetupMethod.AWPS) }
        assertThrows(IllegalArgumentException::class.java) { WifiSetupCredentials("dom🧁","password").validate(SetupMethod.AWPS) }
        WifiSetupCredentials("dom🧁"," password ").validate(SetupMethod.TCP)
    }
    @Test fun cancellationReleasesApWithoutSendingWifi()=runBlocking {
        val link=Link();link.gate=CompletableDeferred()
        val setup=FirstConnectionSetup({link.id},link)
        val job=launch { setup.connectAccessPoint() }
        yield();job.cancelAndJoin();setup.cancel()
        assertEquals(SetupStep.IDLE,setup.progress.value.step)
        assertEquals(0,link.sends);assertTrue(link.releases>0)
    }
    @Test fun awpsWireCarriesSameLocalIdentityAsLaterDiscoveryAndIsSentFiveTimes()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val receiver=java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1",0)).apply { soTimeout=5000 }
        val id="f123NewLocalID56"
        val client=LanSocketClient(scope)
        try {
            val send=async(Dispatchers.IO) { client.provisionUdp(LanSettings(id,"127.0.0.1",udpPort=receiver.localPort,broadcastAddress="127.0.0.1"),credentials.ssid,credentials.password) }
            withContext(Dispatchers.IO) {
                repeat(5) {
                    val packet=java.net.DatagramPacket(ByteArray(256),256)
                    receiver.receive(packet)
                    val plain=ByteTransforms.hash2(packet.data.copyOf(packet.length),123,-1)
                    assertEquals(121,plain.size)
                    assertEquals("#SMT_PAR#",plain.copyOfRange(0,9).toString(Charsets.US_ASCII))
                    assertEquals(id,plain.copyOfRange(105,121).toString(Charsets.US_ASCII))
                    assertEquals(credentials.ssid,plain.copyOfRange(9,41).toString(Charsets.US_ASCII).trimEnd('\u0000'))
                }
            }
            send.await()
            val beacon=ByteTransforms.hash2(DiscoveryCodec.beacon(id,"127.0.0.1",7779,1),123,-1)
            assertEquals(id,beacon.copyOfRange(9,25).toString(Charsets.US_ASCII))
        } finally { receiver.close();client.disconnect();scope.cancel() }
    }
}
