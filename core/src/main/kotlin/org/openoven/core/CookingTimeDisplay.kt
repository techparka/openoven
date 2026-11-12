package org.openoven.core

/** Independent counters: never render elapsed/remaining as elapsed/total. */
object CookingTimeDisplay {
    fun describe(status:OvenStatus):String {
        val remaining=when {
            !status.remainingTimeReliable || status.remainingMinutes !in 0..360 -> "Czas pozostały: oczekiwanie na odczyt"
            status.remainingMinutes==0 && status.power>0 -> "Pozostało 0 min · piekarnik nadal pracuje"
            else -> "Pozostało ${status.remainingMinutes} min"
        }
        val elapsed=if(status.elapsedMinutes in 0 until UNCHANGED) "Upłynęło ${status.elapsedMinutes} min" else "Czas upływu nieznany"
        return "$remaining\n$elapsed"
    }
}
