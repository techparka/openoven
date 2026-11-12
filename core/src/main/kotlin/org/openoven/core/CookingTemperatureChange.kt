package org.openoven.core

/** APK OvenCommand.setFunkcjaGrzania: action=1, only the current stage temperature. */
object CookingTemperatureChange {
    fun range(status:OvenStatus):IntRange = 30..when(status.function) { 8 -> 230; 9 -> 240; else -> 280 }
    fun unavailableReason(status:OvenStatus):String? = when {
        status.power<=0 -> "Nie trwa pieczenie."
        status.controlMode==1 -> "Bieżący tryb pozwala tylko wyłączyć piekarnik."
        status.currentStage !in 1..3 -> "Nie odczytano bieżącego etapu pieczenia."
        status.function !in 1..12 && status.function !in 14..23 && status.function !in 25..35 && status.function !in 46..48 -> "Nie rozpoznano funkcji grzania."
        status.function==10 -> "Rozmrażanie nie udostępnia nastawy temperatury."
        status.targetTemperature !in range(status) -> "Poczekaj na aktualną nastawę temperatury."
        else -> null
    }
    fun command(status:OvenStatus,temperature:Int):OvenCommand {
        require(unavailableReason(status)==null) { unavailableReason(status).orEmpty() }
        require(temperature in range(status)) { "Temperatura: zakres ${range(status).first}–${range(status).last} °C." }
        val body=ByteArray(64) { -1 }
        fun put(at:Int,value:Int) { body[at]=value.toByte();body[at+1]=(value ushr 8).toByte() }
        put(0,65534);put(4,1);put(12,1)
        put(16+(status.currentStage-1)*10,temperature)
        return OvenCommand(268,OvenCommands.checksum(body))
    }
}
