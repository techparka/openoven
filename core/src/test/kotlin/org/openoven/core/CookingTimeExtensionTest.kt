package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CookingTimeExtensionTest {
    private fun word(body:ByteArray,at:Int)=(body[at].toInt() and 255) or ((body[at+1].toInt() and 255) shl 8)
    @Test fun editsOnlyDurationOfSelectedStageWithOriginalLiveEditHeader() {
        for(stage in 1..3) {
            val command=CookingTimeExtension.command(OvenStatus(power=1,currentStage=stage,remainingMinutes=20))
            assertEquals(268,command.address);assertEquals(1,command.opcode);assertEquals(66,command.payload.size)
            val bytes=command.payload
            assertEquals(65534,word(bytes,0));assertEquals(1,word(bytes,4));assertEquals(1,word(bytes,12))
            assertEquals(25,word(bytes,20+(stage-1)*10))
            for(at in 0 until 64 step 2) if(at !in setOf(0,4,12,20+(stage-1)*10)) assertEquals("Changed field $at in stage $stage",UNCHANGED,word(bytes,at))
            assertEquals(65535,(0 until 66 step 2).sumOf { word(bytes,it) } and 65535)
        }
    }
    @Test fun blocksStoppedUnknownOrUnsupportedTimeAndEnforcesReadableSixHourLimit() {
        val running=OvenStatus(power=1,remainingMinutes=20)
        for(status in listOf(running.copy(power=0),running.copy(controlMode=1),running.copy(currentStage=0),
            running.copy(currentStage=4),running.copy(remainingTimeReliable=false),
            running.copy(remainingMinutes=356),running.copy(remainingMinutes=UNCHANGED))) {
            assertNotNull(CookingTimeExtension.unavailableReason(status))
            assertThrows(IllegalArgumentException::class.java) { CookingTimeExtension.command(status) }
        }
        assertEquals(360,word(CookingTimeExtension.command(running.copy(remainingMinutes=355)).payload,20))
    }
    @Test fun cachedOrFallbackDurationCannotBeUsedForAnExtension() {
        val raw=ByteArray(77)
        fun put(at:Int,value:Int) { raw[at]=value.toByte();raw[at+1]=(value ushr 8).toByte() }
        put(0,1);put(32,1);put(42,20)
        assertFalse(StatusCodec.status(raw,OvenStatus(remainingMinutes=20)).remainingTimeReliable)
        put(32,0);put(42,500)
        assertFalse(StatusCodec.status(raw).remainingTimeReliable)
        put(42,20);assertTrue(StatusCodec.status(raw).remainingTimeReliable)
    }
    @Test fun selectedMinuteCountIsUsedAndInvalidOrOversizedSelectionsAreRejected() {
        val status=OvenStatus(power=1,remainingMinutes=20)
        for(extra in listOf(1,7,12,45,340)) assertEquals(20+extra,word(CookingTimeExtension.command(status,extra).payload,20))
        for(extra in listOf(-1,0,341,Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { CookingTimeExtension.command(status,extra) }
        assertNull(CookingTimeExtension.unavailableReason(status.copy(remainingMinutes=359),1))
        assertNotNull(CookingTimeExtension.unavailableReason(status.copy(remainingMinutes=360),1))
    }
    @Test fun allThreeStagesExtendOverRealLoopbackTcpWithoutRestartingOrChangingOtherSettings()=runBlocking {
        for(stage in 1..3) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val initial=OvenStatus(power=1,currentStage=stage,function=6,temperature=175,targetTemperature=185,
                remainingMinutes=20,elapsedMinutes=17,startMinutes=600,endMinutes=620,light=1,rapidHeating=100)
            val client=LanSocketClient(scope,simulationOptions=SimulationOptions(initialStatus=initial))
            try {
                withTimeout(10000) {
                    client.connect(LanSettings("1234567890123456","127.0.0.1",tcpPort=0,demo=true))
                    val before=client.status.value!!
                    client.send(CookingTimeExtension.command(before))
                    val after=client.status.value!!
                    assertEquals(before.copy(remainingMinutes=25,observedAtMillis=after.observedAtMillis),after)
                    client.send(CookingTimeExtension.command(after))
                    assertEquals(30,client.status.value!!.remainingMinutes)
                    assertEquals(17,client.status.value!!.elapsedMinutes)
                }
            } finally { client.disconnect();scope.cancel() }
        }
    }
}
