package org.openoven.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CookingTemperatureChangeTest {
    private fun word(body:ByteArray,at:Int)=(body[at].toInt() and 255) or ((body[at+1].toInt() and 255) shl 8)
    @Test fun sparseLayoutChangesOnlyTemperatureInEachStage() {
        for(stage in 1..3) {
            val command=CookingTemperatureChange.command(OvenStatus(power=1,currentStage=stage,function=6),205)
            assertEquals(268,command.address);assertEquals(258,command.group);assertEquals(1,command.opcode)
            assertEquals(66,command.payload.size)
            val expected=mapOf(0 to 65534,4 to 1,12 to 1,(16+(stage-1)*10) to 205)
            for(at in 0 until 64 step 2) assertEquals("Unexpected field $at in stage $stage",expected[at] ?: UNCHANGED,word(command.payload,at))
            assertEquals(65535,(0 until 66 step 2).sumOf { word(command.payload,it) } and 65535)
        }
    }
    @Test fun rangesAndUnavailableModesAreEnforcedBeforeSending() {
        val running=OvenStatus(power=1,function=6)
        for(status in listOf(running.copy(power=0),running.copy(controlMode=1),running.copy(currentStage=0),
            running.copy(currentStage=4),running.copy(function=10),running.copy(function=13),
            running.copy(function=UNCHANGED),running.copy(targetTemperature=UNCHANGED))) {
            assertNotNull(CookingTemperatureChange.unavailableReason(status))
            assertThrows(IllegalArgumentException::class.java) { CookingTemperatureChange.command(status,180) }
        }
        for((function,max) in listOf(6 to 280,8 to 230,9 to 240)) {
            val status=running.copy(function=function)
            for(temp in listOf(30,max)) CookingTemperatureChange.command(status,temp)
            for(temp in listOf(29,max+1,UNCHANGED)) assertThrows(IllegalArgumentException::class.java) { CookingTemperatureChange.command(status,temp) }
        }
        // Time can be cached during rapid heating; temperature edits do not use it.
        assertNull(CookingTemperatureChange.unavailableReason(running.copy(remainingTimeReliable=false,rapidHeating=100)))
    }
    @Test fun loopbackConfirmsIncreaseAndDecreaseWithoutRestartOrOtherSettingChanges()=runBlocking {
        for(stage in 1..3) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val initial=OvenStatus(power=1,currentStage=stage,function=6,temperature=175,targetTemperature=185,
                remainingMinutes=20,elapsedMinutes=17,startMinutes=600,endMinutes=620,light=1,rapidHeating=100)
            val client=LanSocketClient(scope,simulationOptions=SimulationOptions(initialStatus=initial))
            try {
                withTimeout(10000) {
                    client.connect(LanSettings("1234567890123456","127.0.0.1",tcpPort=0,demo=true))
                    val before=client.status.value!!
                    for(temp in listOf(205,160)) {
                        client.send(CookingTemperatureChange.command(client.status.value!!,temp))
                        val after=client.status.value!!
                        assertEquals(before.copy(targetTemperature=temp,observedAtMillis=after.observedAtMillis),after)
                    }
                }
            } finally { client.disconnect();scope.cancel() }
        }
    }
}
