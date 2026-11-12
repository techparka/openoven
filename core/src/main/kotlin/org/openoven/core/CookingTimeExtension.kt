package org.openoven.core

/** Sparse live edit: same 268 layout as APK OvenCommand.setFunkcjaGrzania (action=1). */
object CookingTimeExtension {
    const val EXTRA_MINUTES=5
    fun unavailableReason(status:OvenStatus,extraMinutes:Int=EXTRA_MINUTES):String?=when {
        extraMinutes !in 1..360 -> "Wybierz liczbę minut od 1 do 360."
        status.power<=0 -> "Nie trwa pieczenie."
        status.controlMode==1 -> "Bieżący tryb pozwala tylko wyłączyć piekarnik."
        status.currentStage !in 1..3 -> "Nie odczytano bieżącego etapu pieczenia."
        !status.remainingTimeReliable || status.remainingMinutes !in 0..360 -> "Poczekaj na aktualny odczyt czasu pieczenia."
        status.remainingMinutes>360-extraMinutes -> "Pozostały czas nie może przekroczyć 6 godzin."
        else -> null
    }
    /** A minute boundary can occur between write and status; never retry the write. */
    fun isConfirmed(before:OvenStatus,after:OvenStatus,extraMinutes:Int):Boolean {
        val requested=before.remainingMinutes+extraMinutes
        return after.power>0 && after.currentStage==before.currentStage && after.function==before.function &&
            after.remainingTimeReliable && (after.remainingMinutes==requested ||
                (after.remainingMinutes==requested-1 && after.elapsedMinutes==before.elapsedMinutes+1))
    }
    fun command(status:OvenStatus,extraMinutes:Int=EXTRA_MINUTES):OvenCommand {
        require(unavailableReason(status,extraMinutes)==null) { unavailableReason(status,extraMinutes).orEmpty() }
        val body=ByteArray(64) { -1 }
        fun put(at:Int,value:Int) { body[at]=value.toByte();body[at+1]=(value ushr 8).toByte() }
        put(0,65534) // live edit, not selecting a new recipe
        put(4,1)
        put(12,1)
        put(20+(status.currentStage-1)*10,status.remainingMinutes+extraMinutes)
        return OvenCommand(268,OvenCommands.checksum(body))
    }
}
