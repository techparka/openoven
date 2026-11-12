package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StatusConsistencyTest {
    private fun raw(elapsed:Int,remaining:Int,rapid:Int=0)=ByteArray(77).also {
        fun put(at:Int,v:Int) { it[at]=v.toByte();it[at+1]=(v ushr 8).toByte() }
        put(0,1);put(10,elapsed);put(32,rapid);put(36,1);put(38,180);put(42,remaining)
    }
    @Test fun screenshotsAreIndependentElapsedAndRemainingCountersAndCanBeExtended() {
        for((elapsed,remaining) in listOf(25 to 15,47 to 7,47 to 0)) {
            val status=StatusCodec.status(raw(elapsed,remaining))
            assertEquals(elapsed,status.elapsedMinutes);assertEquals(remaining,status.remainingMinutes)
            val text=CookingTimeDisplay.describe(status)
            assertTrue(text.contains("Pozostało $remaining min"));assertTrue(text.contains("Upłynęło $elapsed min"))
            assertFalse(text.contains(" z "));assertNull(CookingTimeExtension.unavailableReason(status))
            val bytes=CookingTimeExtension.command(status).payload
            assertEquals(remaining+5,(bytes[20].toInt() and 255) or ((bytes[21].toInt() and 255) shl 8))
        }
    }
    @Test fun invalidOrCachedTimeIsNeverPresentedAsAnActualNineteenOrTwentyMinutes() {
        for(status in listOf(StatusCodec.status(raw(47,65535)),StatusCodec.status(raw(47,500)),
            StatusCodec.status(raw(47,7,1),OvenStatus(remainingMinutes=20)))) {
            assertFalse(status.remainingTimeReliable)
            assertTrue(CookingTimeDisplay.describe(status).contains("oczekiwanie na odczyt"))
            assertFalse(CookingTimeDisplay.describe(status).contains("Pozostało 20"))
            assertNotNull(CookingTimeExtension.unavailableReason(status))
        }
        assertTrue(StatusCodec.status(raw(47,0)).remainingTimeReliable)
    }
    @Test fun extensionConfirmationAllowsARealMinuteBoundaryButNotAnUnchangedOneMinuteWrite() {
        val before=OvenStatus(power=1,function=1,elapsedMinutes=47,remainingMinutes=7)
        assertTrue(CookingTimeExtension.isConfirmed(before,before.copy(remainingMinutes=12),5))
        assertTrue(CookingTimeExtension.isConfirmed(before,before.copy(elapsedMinutes=48,remainingMinutes=11),5))
        assertFalse(CookingTimeExtension.isConfirmed(before,before.copy(remainingMinutes=11),5))
        assertFalse(CookingTimeExtension.isConfirmed(before,before,1))
        assertTrue(CookingTimeExtension.isConfirmed(before,before.copy(elapsedMinutes=48),1))
        assertFalse(CookingTimeExtension.isConfirmed(before,before.copy(remainingMinutes=12,power=0),5))
        assertFalse(CookingTimeExtension.isConfirmed(before,before.copy(remainingMinutes=12,currentStage=2),5))
    }
    @Test fun simulatorCountsDownRemainingWhileElapsedGrowsAndExtensionDoesNotResetElapsed()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val clock=java.util.concurrent.atomic.AtomicLong(0)
        val client=LanSocketClient(scope,simulationOptions=SimulationOptions(minuteMillis=60000,clockNanos=clock::get,
            initialStatus=OvenStatus(power=1,function=1,elapsedMinutes=47,remainingMinutes=7)))
        try {
            withTimeout(10000) {
                client.connect(LanSettings("1234567890123456","127.0.0.1",tcpPort=0,demo=true))
                clock.set(120_000_000_000);client.refresh()
                val before=client.status.value!!
                assertTrue(before.elapsedMinutes>=49);assertTrue(before.remainingMinutes<=5)
                assertEquals(54,before.elapsedMinutes+before.remainingMinutes)
                client.send(CookingTimeExtension.command(before,5))
                val after=client.status.value!!
                assertEquals(59,after.elapsedMinutes+after.remainingMinutes)
                assertTrue(after.elapsedMinutes>=before.elapsedMinutes)
            }
        } finally { client.disconnect();scope.cancel() }
    }
}
