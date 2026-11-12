package org.openoven.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SetupStep { IDLE, CONNECTING_AP, AP_READY, SENDING_WIFI, HOME_REQUIRED, VERIFYING_HOME, COMPLETE, ERROR }
enum class SetupMethod { TCP, AWPS }
data class SetupProgress(val step:SetupStep=SetupStep.IDLE,val message:String="",val acknowledged:Boolean=false) {
    val busy get()=step in setOf(SetupStep.CONNECTING_AP,SetupStep.SENDING_WIFI,SetupStep.VERIFYING_HOME)
}

/** Deliberately does not expose secrets through an automatically generated toString. */
class WifiSetupCredentials(val ssid:String,val password:String) {
    override fun toString()="Wi-Fi credentials [redacted]"
    fun validate(method:SetupMethod) {
        require(ssid.isNotBlank() && ssid!="SmartIN") { "Podaj nazwę domowej sieci Wi-Fi, nie SmartIN." }
        require(ssid.toByteArray(Charsets.UTF_8).size<=32) { "Nazwa sieci może zajmować maksymalnie 32 bajty UTF-8." }
        require(password.toByteArray(Charsets.UTF_8).size<=64) { "Hasło sieci może zajmować maksymalnie 64 bajty UTF-8." }
        require('\u0000' !in ssid && '\u0000' !in password) { "Dane Wi-Fi zawierają niedozwolony znak." }
        OvenCommands.provision(ssid,password)
        if(method==SetupMethod.AWPS) {
            require(ssid==ssid.trim() && password==password.trim()) { "AWPS usuwa skrajne spacje. Dla tej sieci wybierz zwykłą konfigurację TCP." }
            require((ssid+password).all { it.code in 1..255 }) { "AWPS nie obsługuje tych znaków. Wybierz zwykłą konfigurację TCP." }
            DiscoveryCodec.provision("1234567890123456",ssid,password)
        }
    }
}

/** Platform owns network selection; every path uses this installation's existing, stable ID. */
interface FirstConnectionPort {
    suspend fun openAccessPoint(clientIdentity:String)
    fun accessPointAvailable():Boolean=true
    suspend fun sendWifi(clientIdentity:String,credentials:WifiSetupCredentials,method:SetupMethod)
    suspend fun releaseAccessPoint()
    suspend fun readHomeOven(clientIdentity:String):Pair<OvenIdentity,OvenStatus>
    fun rememberAwaitingHome(value:Boolean)
}

class FirstConnectionSetup(private val identity:()->String,private val port:FirstConnectionPort,awaitingHome:Boolean=false) {
    private val state=MutableStateFlow(if(awaitingHome) SetupProgress(SetupStep.HOME_REQUIRED,"Dokończ dodawanie: połącz telefon z domowym Wi-Fi i sprawdź połączenie.") else SetupProgress())
    val progress:StateFlow<SetupProgress> = state
    private val operation=Mutex()
    private var pendingHome=awaitingHome
    private var sessionIdentity:String?=null
    private fun localIdentity()=(sessionIdentity ?: identity().also { sessionIdentity=it }).also {
        require(it.length==16 && it.all { c->c.code in 32..126 }) { "Nieprawidłowa lokalna tożsamość klienta." }
    }
    suspend fun connectAccessPoint()=operation.withLock {
        state.value=SetupProgress(SetupStep.CONNECTING_AP,"Zaakceptuj połączenie ze SmartIN w oknie Androida.")
        try {
            port.openAccessPoint(localIdentity())
            state.value=SetupProgress(SetupStep.AP_READY,"Połączono ze SmartIN. Podaj dane domowego Wi-Fi.")
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { port.releaseAccessPoint();state.value=SetupProgress(SetupStep.ERROR,"Nie połączono ze SmartIN. Otwórz okno WI na piekarniku, sprawdź sieć SmartIN i spróbuj ponownie.") }
    }
    suspend fun configure(credentials:WifiSetupCredentials,method:SetupMethod)=operation.withLock {
        credentials.validate(method) // Invalid input must not disrupt an existing session.
        check(state.value.step==SetupStep.AP_READY) { "Najpierw połącz ze SmartIN." }
        if(!port.accessPointAvailable()) {
            port.releaseAccessPoint()
            state.value=SetupProgress(SetupStep.ERROR,"Utracono SmartIN. Połącz ponownie przed wysłaniem ustawień.")
            return@withLock
        }
        state.value=SetupProgress(SetupStep.SENDING_WIFI,"Przesyłam ustawienia do piekarnika. Nie ponawiam konfiguracji automatycznie.")
        port.rememberAwaitingHome(true) // A lost ACK must not cause automatic re-provisioning after restart.
        pendingHome=true
        var received=false
        try { port.sendWifi(localIdentity(),credentials,method);received=true }
        catch(e:CancellationException) { throw e }
        catch(_:Exception) { /* Device may already have applied settings and left its AP. Verify LAN next. */ }
        finally { port.releaseAccessPoint() }
        val ack=received && method==SetupMethod.TCP
        state.value=SetupProgress(SetupStep.HOME_REQUIRED,when {
            ack->"Piekarnik potwierdził odbiór ustawień. Połącz telefon z domowym Wi-Fi; teraz sprawdzimy odczyt urządzenia."
            received->"Wysłano AWPS bez potwierdzenia. Połącz telefon z domowym Wi-Fi i sprawdź odczyt piekarnika."
            else->"Odbiór ustawień nie został potwierdzony. Piekarnik mógł już zmienić sieć. Najpierw sprawdź połączenie w domowym Wi-Fi."
        },ack)
    }
    suspend fun accessPointLost()=operation.withLock {
        // Loss after sending settings is expected and must not cause another provisioning write.
        if(state.value.step==SetupStep.AP_READY) {
            port.releaseAccessPoint()
            state.value=SetupProgress(SetupStep.ERROR,"Utracono SmartIN. Połącz ponownie przed wysłaniem ustawień.")
        }
    }
    /** Also handles a factory app pairing window WI while the oven is already in home Wi-Fi. */
    suspend fun verifyHome()=operation.withLock {
        state.value=SetupProgress(SetupStep.VERIFYING_HOME,"Wykrywam piekarnik i sprawdzam jego rzeczywisty stan…")
        try {
            port.releaseAccessPoint()
            val (_,status)=port.readHomeOven(localIdentity())
            check(status.observedAtMillis>0 && System.currentTimeMillis()-status.observedAtMillis in 0..15000) { "Brak aktualnego odczytu." }
            port.rememberAwaitingHome(false)
            pendingHome=false
            state.value=SetupProgress(SetupStep.COMPLETE,"Piekarnik dodany. Połączenie i aktualny odczyt zostały potwierdzone.")
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { state.value=SetupProgress(SetupStep.HOME_REQUIRED,"Nie potwierdzono połączenia. Sprawdź domowe Wi-Fi, rozłącz inne aplikacje i otwórz WI na piekarniku. Jeśli Android ukrywa nazwę sieci, włącz lokalizację. Ponowienie odczytu nie wysyła hasła.") }
    }
    suspend fun cancel() {
        port.releaseAccessPoint()
        sessionIdentity=null
        state.value=if(pendingHome)SetupProgress(SetupStep.HOME_REQUIRED,"Połącz telefon z domowym Wi-Fi i sprawdź odczyt. Konfiguracja nie zostanie wysłana ponownie.") else SetupProgress()
    }
    /** Called only after a separately verified normal read, or an explicit profile import. */
    suspend fun reset() {
        port.releaseAccessPoint();sessionIdentity=null;pendingHome=false
        port.rememberAwaitingHome(false);state.value=SetupProgress()
    }
}
