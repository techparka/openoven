package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SmartInLossTest {
    private class Port:FirstConnectionPort {
        var available=true;var sends=0;var pending=false
        var duringSend:(suspend ()->Unit)?=null
        override suspend fun openAccessPoint(clientIdentity:String) { available=true }
        override fun accessPointAvailable()=available
        override suspend fun sendWifi(clientIdentity:String,credentials:WifiSetupCredentials,method:SetupMethod) { sends++;duringSend?.invoke() }
        override suspend fun releaseAccessPoint() { available=false }
        override suspend fun readHomeOven(clientIdentity:String)=OvenIdentity(9137,362,259,0,18,2,42,0) to OvenStatus(observedAtMillis=System.currentTimeMillis())
        override fun rememberAwaitingHome(value:Boolean) { pending=value }
    }
    @Test fun lostApUpdatesReadyStateAndDoesNotSendCredentials()=runBlocking {
        val port=Port();val setup=FirstConnectionSetup({"1234567890123456"},port)
        setup.connectAccessPoint();assertEquals(SetupStep.AP_READY,setup.progress.value.step)
        port.available=false;setup.accessPointLost()
        assertEquals(SetupStep.ERROR,setup.progress.value.step)
        assertEquals(0,port.sends);assertFalse(port.pending)
    }
    @Test fun availabilityCheckCatchesLossBeforeItsCallbackIsProcessed()=runBlocking {
        val port=Port();val setup=FirstConnectionSetup({"1234567890123456"},port)
        setup.connectAccessPoint();port.available=false
        setup.configure(WifiSetupCredentials("Synthetic","test-password"),SetupMethod.TCP)
        assertEquals(SetupStep.ERROR,setup.progress.value.step);assertEquals(0,port.sends);assertFalse(port.pending)
    }
    @Test fun expectedLossDuringConfigurationStillVerifiesHomeAndNeverReplaysPassword()=runBlocking {
        val port=Port();val setup=FirstConnectionSetup({"1234567890123456"},port)
        var lost:Job?=null
        port.duringSend={ port.available=false;lost=launch { setup.accessPointLost() };yield() }
        setup.connectAccessPoint();setup.configure(WifiSetupCredentials("Synthetic","test-password"),SetupMethod.TCP)
        lost?.join();assertEquals(SetupStep.HOME_REQUIRED,setup.progress.value.step)
        setup.verifyHome();assertEquals(SetupStep.COMPLETE,setup.progress.value.step);assertEquals(1,port.sends)
    }
}
