package org.openoven.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openoven.core.*
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises connectReal, including its retry/service/network policy, without any real network. */
@RunWith(AndroidJUnit4::class)
class LanLifecycleTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun waitFor(message:String,condition:()->Boolean) {
        val end=System.currentTimeMillis()+6000
        while(System.currentTimeMillis()<end) { if(condition())return;Thread.sleep(20) }
        fail(message)
    }
    private class Platform:ConnectionRuntime {
        @Volatile var address="127.0.0.1"
        @Volatile var bound:String?=null
        @Volatile var owner:Long?=null
        @Volatile var now=0L
        var denyService=false
        var denyRetry=false
        val phases=CopyOnWriteArrayList<String>()
        val delays=Channel<Long>(Channel.UNLIMITED)
        val proceed=Channel<Unit>(Channel.UNLIMITED)
        override fun localAddress()=address
        override fun bindWifi(address:String) { bound=address }
        override fun clearBinding() { bound=null }
        override fun startService(owner:Long,phase:String) {
            if(denyService || (denyRetry && phase.startsWith("Ponowne")))error("Test: service unavailable")
            this.owner=owner;phases+=phase
        }
        override fun stopService() { owner=null }
        override fun nowMillis()=now
        override suspend fun waitBeforeRetry(millis:Long) { delays.send(millis);proceed.receive() }
        fun nextDelay()=runBlocking { withTimeout(6000) { delays.receive() } }
        fun retry() { check(proceed.trySend(Unit).isSuccess) }
    }
    private class Device:OvenClient {
        override val connection=MutableStateFlow(ConnectionInfo(ConnectionState.DISCONNECTED))
        override val identity=MutableStateFlow<OvenIdentity?>(null)
        override val status=MutableStateFlow<OvenStatus?>(null)
        val connections=CopyOnWriteArrayList<LanSettings>()
        val writes=CopyOnWriteArrayList<OvenCommand>()
        var failures=0
        var gate:CompletableDeferred<Unit>?=null
        var active=0;var maxActive=0
        override suspend fun connect(settings:LanSettings) {
            connections+=settings;connection.value=ConnectionInfo(ConnectionState.AUTHENTICATING)
            gate?.await()
            if(failures>0) { failures--;connection.value=ConnectionInfo(ConnectionState.ERROR);error("Test: unavailable") }
            active++;maxActive=maxOf(maxActive,active)
            identity.value=OvenIdentity(9137,362,259,0,18,2,42,0)
            status.value=OvenStatus(observedAtMillis=System.currentTimeMillis())
            connection.value=ConnectionInfo(ConnectionState.CONNECTED)
        }
        fun drop() { active=0;connection.value=ConnectionInfo(ConnectionState.ERROR) }
        override suspend fun disconnect() { active=0;connection.value=ConnectionInfo(ConnectionState.DISCONNECTED);status.value=null;identity.value=null }
        override suspend fun refresh() { status.value=status.value?.copy(observedAtMillis=System.currentTimeMillis()) }
        override suspend fun send(command:OvenCommand) { writes+=command;refresh() }
    }
    private fun scenario(block:(AppController,Platform,Device)->Unit) {
        val platform=Platform();val device=Device();lateinit var controller:AppController
        main {
            controller=AppController.isolated(context,platform,device)
            controller.settings.ipAddress="";controller.settings.broadcastAddress="";controller.settings.deviceAddress=""
            controller.settings.tcpPort=7779;controller.settings.udpPort=7777;controller.settings.autoConnect=false
        }
        try { block(controller,platform,device) }
        finally { runBlocking { withContext(Dispatchers.Main) { controller.closeIsolated() } } }
    }
    @Test fun noRetryDropReleasesServiceBindingAndTransport()=scenario { c,p,d ->
        main { c.connectReal() };waitFor("Not connected") { c.hasFreshStatus }
        assertNotNull(p.owner);assertNotNull(p.bound)
        main { d.drop() }
        waitFor("Resources not released") { p.owner==null && p.bound==null && d.connection.value.state==ConnectionState.DISCONNECTED }
        assertEquals(1,d.connections.size)
    }
    @Test fun failureBeforeHandshakeAndDeniedServiceHaveNoOrphanedResources()=scenario { c,p,d ->
        main { d.failures=1;c.connectReal() }
        waitFor("Failure not cleaned") { d.connections.size==1 && p.owner==null && p.bound==null }
        main { p.denyService=true;c.settings.autoConnect=true;c.connectReal() }
        waitFor("Service error not reported") { c.error.value?.message?.contains("service unavailable")==true }
        assertEquals(1,d.connections.size);assertNull(p.owner);assertNull(p.bound)
    }
    @Test fun serviceFailureWhileEnteringBackoffIsHandledAndCleaned()=scenario { c,p,d ->
        main { c.settings.autoConnect=true;c.connectReal() };waitFor("Not connected") { c.hasFreshStatus }
        main { p.denyRetry=true;d.drop() }
        waitFor("Retry service error was not handled") { p.owner==null && p.bound==null && c.error.value?.message?.contains("Nie można utrzymać usługi")==true }
        assertEquals(1,d.connections.size);assertFalse(c.hasFreshStatus)
    }
    @Test fun retryRebindsNewAddressAndDoesNotReplayCommands()=scenario { c,p,d ->
        main { c.settings.autoConnect=true;c.connectReal() };waitFor("Not connected") { c.hasFreshStatus }
        main { c.execute(OvenCommands.common(light=100)) };waitFor("Write missing") { d.writes.size==1 }
        main { d.drop() };assertEquals(2000L,p.nextDelay());assertNull(p.bound);assertNotNull(p.owner)
        main { p.address="127.0.0.2";p.retry() };waitFor("Not reconnected") { d.connections.size==2 && c.hasFreshStatus }
        assertEquals("127.0.0.2",p.bound);assertEquals(1,d.writes.size);assertEquals(1,d.maxActive)
    }
    @Test fun explicitDisconnectDuringBackoffCancelsRetry()=scenario { c,p,d ->
        main { d.failures=1;c.settings.autoConnect=true;c.connectReal() };assertEquals(2000L,p.nextDelay())
        main { c.disconnect() };waitFor("Not stopped") { p.owner==null && p.bound==null }
        main { p.retry() };Thread.sleep(150)
        assertEquals(1,d.connections.size);assertFalse(c.settings.autoConnect)
    }
    @Test fun qrImportDuringBackoffCannotReviveOldIdentityOrLoop()=scenario { c,p,d ->
        main { d.failures=1;c.settings.autoConnect=true;c.connectReal() };p.nextDelay()
        main { c.importConnectionProfile(ConnectionProfile("Synthetic","1234567890123456",7779,7777)) }
        waitFor("Imported profile not connected") { d.connections.size==2 && c.hasFreshStatus }
        assertEquals("1234567890123456",d.connections.last().clientIdentity)
        main { p.retry() };Thread.sleep(100)
        assertEquals(2,d.connections.size);assertEquals(1,d.maxActive)
    }
    @Test fun lostServiceEndsSessionAndStaleDestroyCannotCloseNewOwner()=scenario { c,p,d ->
        main { c.settings.autoConnect=true;c.connectReal() };waitFor("Not connected") { c.hasFreshStatus }
        val previous=p.owner!!
        main { p.owner=null;c.connectionServiceDestroyed(previous) }
        waitFor("Lost service not cleaned") { !c.hasFreshStatus && p.bound==null }
        assertTrue(c.settings.autoConnect);assertEquals(1,d.connections.size)
        main { c.resumeConfiguredConnection() };waitFor("Not resumed") { d.connections.size==2 && c.hasFreshStatus }
        main { c.connectionServiceDestroyed(previous) }
        assertTrue(c.hasFreshStatus);assertNotEquals(previous,p.owner)
    }
    @Test fun stableSessionResetsBackoffButShortFailuresDoNot()=scenario { c,p,d ->
        main { d.failures=3;c.settings.autoConnect=true;c.connectReal() }
        for(delay in listOf(2000L,4000L,8000L)) { assertEquals(delay,p.nextDelay());p.retry() }
        waitFor("Stable connection missing") { c.hasFreshStatus }
        main { p.now=31000;d.drop() };assertEquals(2000L,p.nextDelay())
    }
    @Test fun cancelledHandshakeDoesNotLeaveSecondActiveSession()=scenario { c,p,d ->
        main { d.gate=CompletableDeferred();c.connectReal() };waitFor("Handshake missing") { d.connections.size==1 }
        val previous=p.owner!!
        main { d.gate=null;p.address="127.0.0.2";c.connectReal() }
        waitFor("Replacement session missing") { d.connections.size==2 && c.hasFreshStatus }
        main { c.connectionServiceDestroyed(previous) }
        assertEquals("127.0.0.2",p.bound);assertTrue(c.hasFreshStatus);assertEquals(1,d.maxActive)
    }
}
