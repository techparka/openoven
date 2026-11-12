package org.openoven.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.openoven.core.*
import java.util.ArrayDeque

data class AppError(val message:String,val connectionRelated:Boolean,val session:Int)

/** Process-owned controller; remembered local sessions never re-provision Wi-Fi. */
class AppController private constructor(private val context: Context,
    private val runtime:ConnectionRuntime=AndroidConnectionRuntime(context),injectedClient:OvenClient?=null) {
    companion object {
        @Volatile private var instance:AppController?=null
        fun existing():AppController?=instance
        @androidx.annotation.VisibleForTesting
        fun isolated(context:Context,runtime:ConnectionRuntime,client:OvenClient)=AppController(context.applicationContext,runtime,client)
        fun get(context:Context):AppController = instance ?: synchronized(this) {
            instance ?: AppController(context.applicationContext).also { instance=it }
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val settings=AppSettings(context)
    val recipes=RecipeStore(context)
    private val logs=ArrayDeque<String>()
    private val _error=MutableStateFlow<AppError?>(null)
    val error:StateFlow<AppError?> = _error
    val client:OvenClient=injectedClient ?: LanSocketClient(scope,::log)
    private var connectionJob:Job?=null
    private var generation=0
    val sessionToken:Int get()=generation
    val hasFreshStatus:Boolean get()=client.connection.value.state==ConnectionState.CONNECTED &&
        client.status.value?.observedAtMillis?.let { System.currentTimeMillis()-it in 0..15000 }==true
    val supportsControl:Boolean get()=client.identity.value?.deviceId in setOf(53274,9137)
    val canControl:Boolean get()=hasFreshStatus && supportsControl
    fun requireSession(token:Int) { check(token==generation) { "Połączenie zmieniło się. Otwórz nastawy ponownie." } }
    private var nextServiceOwner=0L
    private var serviceOwner:Long?=null
    private fun foreground(owner:Long,phase:String) { serviceOwner=owner;runtime.startService(owner,phase) }
    private fun stopForeground(owner:Long?=serviceOwner) {
        if(owner!=null && owner==serviceOwner) { serviceOwner=null;runtime.stopService() }
    }
    private val setupNetwork:SetupWifiNetwork=SetupWifiNetwork(context) { scope.launch { firstSetup.accessPointLost() } }
    private var setupAp:LanSettings?=null
    private var setupTargetSsid:String?=null
    val firstSetup:FirstConnectionSetup=FirstConnectionSetup({ settings.identity },object:FirstConnectionPort {
        override suspend fun openAccessPoint(clientIdentity:String) {
            foreground(serviceOwner ?: ++nextServiceOwner,"Konfiguracja piekarnika")
            client.disconnect()
            val ip=setupNetwork.connectSmartIn()
            // LAN overrides from an existing profile must never leak into the SmartIN subnet.
            setupAp=LanSettings(clientIdentity,ip)
        }
        override fun accessPointAvailable()=setupAp?.let { setupNetwork.currentAccessPointAddress()==it.localAddress }==true
        override suspend fun sendWifi(clientIdentity:String,credentials:WifiSetupCredentials,method:SetupMethod) {
            val ap=checkNotNull(setupAp) { "Najpierw połącz ze SmartIN." }
            check(accessPointAvailable()) { "Utracono SmartIN. Połącz ponownie przed wysłaniem ustawień." }
            check(ap.clientIdentity==clientIdentity) { "Tożsamość sesji uległa zmianie." }
            setupTargetSsid=credentials.ssid
            if(method==SetupMethod.AWPS) (client as LanSocketClient).provisionUdp(ap,credentials.ssid,credentials.password)
            else (client as LanSocketClient).provisionTcp(ap,credentials.ssid,credentials.password)
        }
        override suspend fun releaseAccessPoint() { setupNetwork.release();setupAp=null }
        override suspend fun readHomeOven(clientIdentity:String):Pair<OvenIdentity,OvenStatus> {
            foreground(serviceOwner ?: ++nextServiceOwner,"Konfiguracja piekarnika")
            client.disconnect()
            val ip=setupNetwork.homeAddress(setupTargetSsid)
            client.connect(LanSettings(clientIdentity,ip))
            val identity=checkNotNull(client.identity.value)
            val status=checkNotNull(client.status.value)
            settings.ipAddress="";settings.broadcastAddress="";settings.deviceAddress=""
            settings.tcpPort=7779;settings.udpPort=7777;settings.autoConnect=true
            return identity to status
        }
        override fun rememberAwaitingHome(value:Boolean) { settings.setupAwaitingHome=value }
    },settings.setupAwaitingHome)
    private val addingTime=MutableStateFlow(false)
    val isAddingTime:StateFlow<Boolean> = addingTime
    private val changingTemperature=MutableStateFlow(false)
    val isChangingTemperature:StateFlow<Boolean> = changingTemperature
    private var commandEpoch=0
    private val starting=MutableStateFlow(false)
    val isStarting:StateFlow<Boolean> = starting
    private data class Batch(val generation:Int,val commands:List<OvenCommand>,val extraMinutes:Int=0,val expectedStage:Int?=null,
        val temperature:Int?=null,val expectedFunction:Int?=null,val startsCooking:Boolean=false,val epoch:Int=0,val urgentStop:Boolean=false,
        val createdAt:Long=android.os.SystemClock.elapsedRealtime())
    private val commands=Channel<Batch>(32)
    private val stops=Channel<Batch>(Channel.CONFLATED)
    init {
        scope.launch {
            while(isActive) {
                // Biased select gives a pending STOP precedence without concurrent socket writes.
                val batch=select<Batch> { stops.onReceive { it };commands.onReceive { it } }
                var attemptedWrites=0
                try {
                    if(batch.generation!=generation || batch.epoch!=commandEpoch) continue
                    if(!batch.urgentStop) check(android.os.SystemClock.elapsedRealtime()-batch.createdAt<=30000) { "Polecenie zbyt długo czekało w kolejce. Wybierz działanie ponownie." }
                    if(batch.extraMinutes>0) {
                        requireFreshConnection()
                        client.refresh() // Compute from the device after earlier queued commands, not from a stale tap.
                        check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się; polecenie pominięte." }
                        requireFreshConnection()
                        val before=checkNotNull(client.status.value)
                        check(batch.expectedStage==null || before.currentStage==batch.expectedStage) { "Etap pieczenia zmienił się. Otwórz wybór czasu ponownie." }
                        val command=CookingTimeExtension.command(before,batch.extraMinutes)
                        validateCommand(command)
                        client.send(command) // Includes a fresh read; never retries the write.
                        check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się." }
                        var after=checkNotNull(client.status.value)
                        val deadline=android.os.SystemClock.elapsedRealtime()+3000
                        while(after.currentStage==before.currentStage && after.power>0 &&
                            !CookingTimeExtension.isConfirmed(before,after,batch.extraMinutes) && android.os.SystemClock.elapsedRealtime()<deadline) {
                            delay(200);client.refresh() // Read-only confirmation of a possibly delayed appliance update.
                            check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się." }
                            after=checkNotNull(client.status.value)
                        }
                        check(CookingTimeExtension.isConfirmed(before,after,batch.extraMinutes)) {
                            "Nie potwierdzono nowego czasu. Sprawdź odczyt piekarnika przed kolejnym dodaniem minut."
                        }
                    }
                    if(batch.temperature!=null) {
                        requireFreshConnection();client.refresh()
                        check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się; polecenie pominięte." }
                        requireFreshConnection()
                        val before=checkNotNull(client.status.value)
                        check(before.currentStage==batch.expectedStage && before.function==batch.expectedFunction) { "Etap lub funkcja grzania zmieniły się. Wybierz temperaturę ponownie." }
                        val command=CookingTemperatureChange.command(before,batch.temperature)
                        validateCommand(command);client.send(command) // Never repeat an unconfirmed write.
                        check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się." }
                        var after=checkNotNull(client.status.value)
                        val deadline=android.os.SystemClock.elapsedRealtime()+3000
                        while(after.power>0 && after.currentStage==before.currentStage && after.function==before.function &&
                            after.targetTemperature!=batch.temperature && android.os.SystemClock.elapsedRealtime()<deadline) {
                            delay(200);client.refresh()
                            check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się." }
                            after=checkNotNull(client.status.value)
                        }
                        check(after.power>0 && after.currentStage==before.currentStage && after.function==before.function && after.targetTemperature==batch.temperature) {
                            "Nie potwierdzono nowej temperatury. Sprawdź aktualną nastawę przed kolejną zmianą."
                        }
                    }
                    for(command in batch.commands) {
                        check(batch.generation==generation && batch.epoch==commandEpoch) { "Sesja zmieniła się; polecenie pominięte." }
                        requireFreshConnection()
                        validateCommand(command)
                        attemptedWrites++
                        client.send(command)
                    }
                } catch(e:CancellationException) {
                    currentCoroutineContext().ensureActive()
                    log("Nieaktualna sesja: pominięto pozostałe polecenia bez ponawiania")
                }
                catch(e:Exception) { if(batch.generation==generation && batch.epoch==commandEpoch) showError(if(batch.startsCooking && attemptedWrites>0) "Nie potwierdzono całego rozpoczęcia. Część poleceń mogła zostać przyjęta. Sprawdź stan piekarnika przed kolejną próbą. " + e.message.orEmpty() else e.message ?: "Polecenie nie zostało potwierdzone. Nie ponawiamy go automatycznie.") else log("Pominięto błąd polecenia poprzedniej sesji; brak ponowienia zapisu") }
                finally { releasePendingFlags(batch) }
            }
        }
    }
    private fun releasePendingFlags(batch:Batch) {
        if(batch.startsCooking) starting.value=false
        if(batch.extraMinutes>0) addingTime.value=false
        if(batch.temperature!=null) changingTemperature.value=false
    }
    fun clearError() { _error.value=null }
    fun noteCookingNavigation() { log("UI: otwieranie nastaw; stan=${client.connection.value.state}; aktualny=$hasFreshStatus") }
    fun noteCookingFormReady() { log("UI: formularz nastaw gotowy") }
    fun diagnosticEvents():String = synchronized(logs) { logs.toList().takeLast(60).joinToString("\n") }
    fun connectReal() = connect(false)
    fun connectDemo(options:SimulationOptions=SimulationOptions(minuteMillis=60000)) { settings.autoConnect=false; stopForeground(); connect(true,options) }
    fun resumeConfiguredConnection() {
        if(settings.autoConnect && !settings.setupAwaitingHome && firstSetup.progress.value.step in setOf(SetupStep.IDLE,SetupStep.COMPLETE) && connectionJob?.isActive!=true) connect(false)
    }
    fun connectionProfile()=ConnectionProfile(settings.ovenName,settings.identity,settings.tcpPort,settings.udpPort,settings.deviceAddress).validated()
    fun importConnectionProfile(profile:ConnectionProfile) {
        profile.validated()
        val previous=connectionJob
        previous?.cancel();++generation
        val next=scope.launch(start=CoroutineStart.LAZY) {
            withContext(NonCancellable) { client.disconnect();previous?.join() }
            currentCoroutineContext().ensureActive()
            firstSetup.reset();setupTargetSsid=null
            client.disconnect();clearNetworkBinding()
            settings.identity=profile.clientIdentity;settings.ovenName=profile.name
            settings.tcpPort=profile.tcpPort;settings.udpPort=profile.udpPort
            settings.ipAddress="";settings.broadcastAddress="";settings.deviceAddress=profile.ovenAddress
            settings.autoConnect=true
            log("Zastosowano profil lokalny z QR; bez zmiany Wi-Fi piekarnika")
            connectReal()
        }
        connectionJob=next;next.start()
    }
    private fun connect(demo:Boolean,demoOptions:SimulationOptions=SimulationOptions()) {
        ++generation
        val previous=connectionJob
        previous?.cancel();clearError()
        val next=scope.launch(start=CoroutineStart.LAZY) {
            withContext(NonCancellable) { client.disconnect();previous?.join() }
            currentCoroutineContext().ensureActive()
            firstSetup.cancel();setupTargetSsid=null
            val owner=++nextServiceOwner
            var retries=0
            var demoConnected=false
            try {
                do {
                    val session=generation
                    var connectedAt:Long?=null
                    var serviceReady=demo
                    try {
                        if(!demo) { foreground(owner,"Łączenie z piekarnikiem…");serviceReady=true }
                        client.disconnect();clearNetworkBinding()
                        val config=if(demo)LanSettings(settings.identity,"127.0.0.1",tcpPort=0,demo=true) else realSettings()
                        if(!demo)bindWifi(config.localAddress)
                        if(demo)(client as LanSocketClient).connectDemo(config,demoOptions) else client.connect(config)
                        currentCoroutineContext().ensureActive()
                        check(session==generation) { "Sesja zmieniła się." }
                        connectedAt=runtime.nowMillis()
                        if(!demo && settings.setupAwaitingHome) { firstSetup.reset();bindWifi(config.localAddress) }
                        clearError()
                        if(demo) { demoConnected=true;break }
                        foreground(owner,"Połączono z piekarnikiem")
                        client.connection.first { it.state==ConnectionState.ERROR || it.state==ConnectionState.DISCONNECTED }
                        log("Sesja lokalna zakończona; polecenia nie będą ponawiane")
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) {
                        if(session==generation) showError(e.message ?: "Nie można nawiązać połączenia.",connectionRelated=true)
                    } finally {
                        if(!demo && serviceOwner==owner) withContext(NonCancellable) {
                            client.disconnect();clearNetworkBinding()
                        }
                    }
                    if(demo || !settings.autoConnect || !serviceReady) break
                    // Reset only after a stable period; short flapping keeps the backoff.
                    if(connectedAt?.let { runtime.nowMillis()-it>=30000 }==true) retries=0
                    ++generation
                    ++retries
                    val waitMillis=(1000L shl retries.coerceAtMost(4)).coerceAtMost(15000)
                    foreground(owner,"Ponowne łączenie za ${waitMillis/1000} s")
                    log("Ponowienie lokalnego połączenia za ${waitMillis/1000}s; bez zmiany Wi-Fi")
                    runtime.waitBeforeRetry(waitMillis)
                } while(isActive)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(serviceOwner==owner) showError("Nie można utrzymać usługi połączenia. Otwórz aplikację i spróbuj ponownie. " + e.message.orEmpty(),connectionRelated=true)
            } finally {
                if(!demo && serviceOwner==owner) withContext(NonCancellable) {
                    client.disconnect();clearNetworkBinding();stopForeground(owner)
                }
                if(demo && !demoConnected) withContext(NonCancellable) { client.disconnect();clearNetworkBinding() }
            }
        }
        connectionJob=next;next.start()
    }
    /** Losing the service ends its session; it never silently resumes writes or restarts in the background. */
    fun connectionServiceDestroyed(owner:Long) {
        if(serviceOwner!=owner) return
        serviceOwner=null
        ++generation
        val previous=connectionJob;previous?.cancel()
        val next=scope.launch(start=CoroutineStart.LAZY) {
            withContext(NonCancellable) { client.disconnect();previous?.join();firstSetup.cancel();clearNetworkBinding() }
            currentCoroutineContext().ensureActive()
            showError("Usługa połączenia została zatrzymana. Otwórz aplikację i połącz ponownie.",connectionRelated=true)
        }
        connectionJob=next;next.start()
    }
    @androidx.annotation.VisibleForTesting
    suspend fun closeIsolated() { disconnect();connectionJob?.join();scope.cancel() }
    fun disconnect() {
        settings.autoConnect=false
        stopForeground()
        ++generation
        val old=connectionJob
        old?.cancel()
        val next=scope.launch(start=CoroutineStart.LAZY) {
            withContext(NonCancellable) {
                client.disconnect();old?.join();firstSetup.cancel();setupTargetSsid=null;client.disconnect();clearNetworkBinding();clearError()
            }
        }
        connectionJob=next;next.start()
    }
    private fun setupOperation(action:suspend ()->Unit) {
        val old=connectionJob
        old?.cancel()
        val session=++generation
        val next=scope.launch(start=CoroutineStart.LAZY) {
            withContext(NonCancellable) { client.disconnect();old?.join() }
            currentCoroutineContext().ensureActive()
            clearError()
            try {
                action()
                if(firstSetup.progress.value.step==SetupStep.COMPLETE) {
                    log("Dodanie zakończone: potwierdzono tożsamość urządzenia i aktualny stan")
                    client.connection.first { it.state==ConnectionState.ERROR || it.state==ConnectionState.DISCONNECTED }
                    if(session==generation && settings.autoConnect) connectReal()
                } else if(firstSetup.progress.value.step!=SetupStep.AP_READY) stopForeground()
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(session==generation) showError("Nie udało się dokończyć dodawania. Sprawdź krok w kreatorze i spróbuj ponownie.") }
        }
        connectionJob=next;next.start()
    }
    fun connectSetupAccessPoint()=setupOperation { firstSetup.connectAccessPoint() }
    fun configureFirstWifi(ssid:String,password:String,method:SetupMethod) {
        val credentials=WifiSetupCredentials(ssid,password)
        credentials.validate(method)
        setupOperation {
            firstSetup.configure(credentials,method)
            // Android normally returns to its previous saved network after releasing the request.
            repeat(20) {
                if(setupNetwork.homeNetworkAvailable(ssid)) { firstSetup.verifyHome();return@setupOperation }
                delay(500)
            }
        }
    }
    fun verifyFirstHomeConnection()=setupOperation { firstSetup.verifyHome() }
    fun cancelFirstSetup() { disconnect() }
    fun refresh() {
        val session=generation
        scope.launch {
            try { client.refresh();if(session==generation && _error.value?.connectionRelated==true) clearError() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { if(session==generation) showError(e.message ?: "Nie można odczytać stanu.",connectionRelated=true) }
        }
    }
    fun execute(command:OvenCommand) = executeBatch(listOf(command))
    fun addMinutes(minutes:Int,expectedStage:Int?=null,expectedSession:Int=generation) {
        requireSession(expectedSession)
        check(!addingTime.value && !changingTemperature.value) { "Poczekaj na potwierdzenie poprzedniej zmiany." }
        requireFreshConnection()
        val status=checkNotNull(client.status.value)
        require(CookingTimeExtension.unavailableReason(status,minutes)==null) { CookingTimeExtension.unavailableReason(status,minutes).orEmpty() }
        check(expectedStage==null || status.currentStage==expectedStage) { "Etap pieczenia zmienił się. Otwórz wybór czasu ponownie." }
        addingTime.value=true
        if(!commands.trySend(Batch(generation,emptyList(),extraMinutes=minutes,expectedStage=expectedStage,epoch=commandEpoch)).isSuccess) {
            addingTime.value=false;error("Nie udało się dodać czasu. Spróbuj ponownie po odświeżeniu odczytu.")
        }
    }
    fun changeTemperature(temperature:Int,expectedStage:Int,expectedFunction:Int,expectedSession:Int=generation) {
        requireSession(expectedSession)
        check(!changingTemperature.value && !addingTime.value) { "Poczekaj na potwierdzenie poprzedniej zmiany." }
        requireFreshConnection()
        val status=checkNotNull(client.status.value)
        check(status.currentStage==expectedStage && status.function==expectedFunction) { "Etap lub funkcja grzania zmieniły się. Wybierz temperaturę ponownie." }
        validateCommand(CookingTemperatureChange.command(status,temperature))
        changingTemperature.value=true
        if(!commands.trySend(Batch(generation,emptyList(),expectedStage=expectedStage,temperature=temperature,expectedFunction=expectedFunction,epoch=commandEpoch)).isSuccess) {
            changingTemperature.value=false;error("Nie udało się zmienić temperatury. Odśwież odczyt.")
        }
    }
    fun executeBatch(batch:List<OvenCommand>) {
        require(batch.isNotEmpty())
        requireFreshConnection()
        batch.forEach(::validateCommand)
        val stop=batch.size==1 && batch[0].group==258 && batch[0].address==228 && batch[0].opcode==1 &&
            batch[0].payload.size>=2 && batch[0].payload[0].toInt()==0 && batch[0].payload[1].toInt()==0
        if(stop) {
            ++commandEpoch // Cancel queued intentions and the remainder of a start, not an in-flight frame.
            while(true) releasePendingFlags(commands.tryReceive().getOrNull() ?: break)
            check(stops.trySend(Batch(generation,batch,epoch=commandEpoch,urgentStop=true)).isSuccess)
            return
        }
        val starts=batch.any { it.address==268 && it.payload.size>=6 && it.payload[4].toInt()==0 && it.payload[5].toInt()==0 } &&
            batch.any { it.address==228 && it.payload.size>=2 && (it.payload[0].toInt() and 255)==100 && it.payload[1].toInt()==0 }
        if(starts) { check(!starting.value) { "Rozpoczynanie już trwa. Poczekaj na potwierdzenie." };starting.value=true }
        if(!commands.trySend(Batch(generation,batch,startsCooking=starts,epoch=commandEpoch)).isSuccess) {
            if(starts) starting.value=false
            error("Kolejka poleceń jest pełna. Poczekaj na zakończenie bieżących działań.")
        }
    }
    fun remember(stages:List<CookingStage>) {
        settings.saveHistory((listOf(stages)+settings.history().filterNot { it==stages }).take(5))
    }
    fun exportDiagnostics(callback:(String)->Unit) {
        val id=client.identity.value
        val status=client.status.value
        val record=JSONObject().put("application","Open Oven "+context.packageManager.getPackageInfo(context.packageName,0).versionName).put("androidApi",Build.VERSION.SDK_INT)
            .put("demo",client.connection.value.demo).put("connection",client.connection.value.state.name)
            .put("deviceModel",id?.deviceId ?: JSONObject.NULL).put("firmware",id?.softwareVersion ?: JSONObject.NULL)
            .put("status",status?.let { JSONObject().put("temperature",it.temperature).put("power",it.power).put("light",it.light)
                .put("door",it.door).put("errors",it.errors).put("observedAtMillis",it.observedAtMillis) } ?: JSONObject.NULL)
            .put("logs",org.json.JSONArray(synchronized(logs) { logs.toList() }))
            .put("privacy","Omitted client ID, Android ID, serial, IP/MAC, SSID, passwords and raw packets")
        callback(record.toString(2))
    }
    private fun realSettings():LanSettings {
        val ip=settings.ipAddress.ifBlank { wifiAddress() }
        require(ip.isNotBlank()) { "Brak adresu IPv4 Wi-Fi. Połącz telefon z siecią domową lub wpisz adres telefonu." }
        require(ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && ip.split('.').all { it.toInt() in 0..255 }) { "Nieprawidłowy adres IPv4 telefonu." }
        fun address(value:String):String? = value.takeIf { it.isNotBlank() }?.also {
            require(it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && it.split('.').all { part->part.toInt() in 0..255 }) { "Nieprawidłowy adres IPv4 w ustawieniach." }
        }
        return LanSettings(settings.identity,ip,settings.tcpPort,settings.udpPort,address(settings.broadcastAddress),address(settings.deviceAddress))
    }
    private fun wifiAddress():String=runtime.localAddress()
    private fun bindWifi(address:String) { runtime.bindWifi(address);log("Ruch aplikacji przypisany do Wi-Fi") }
    private fun clearNetworkBinding()=runtime.clearBinding()
    private fun requireFreshConnection() {
        check(hasFreshStatus) { "Brak połączenia lub aktualnego stanu — odśwież odczyt." }
    }
    private fun validateCommand(command:OvenCommand) {
        require(command.payload.size<=4096)
        val status=client.status.value!!
        fun word(at:Int)=(command.payload[at].toInt() and 255) or ((command.payload[at+1].toInt() and 255) shl 8)
        if(command.group==258 && status.controlMode==1 && status.power>0) {
            check(command.address==228 && command.payload.size==40 && word(0)==0) {
                "Bieżący tryb sterowania pozwala tylko wyłączyć piekarnik."
            }
        }
        check(client.identity.value?.deviceId!=1264) { "Model Amica IN wymaga odrębnego sterownika. Dostępna jest diagnostyka." }
        // 9137/firmware362 observed on this oven; the original generic TCP controller
        // uses the same 258/228 and 258/268 layout. 53274<355 is its legacy exception.
        check(client.identity.value?.deviceId in setOf(53274,9137)) { "Nierozpoznany model — dostępny jest odczyt i diagnostyka, bez sterowania." }
        require(command.address!=197) { "Aktualizacja firmware nie jest obsługiwana w prototypie." }
        if(command.group==258 && command.address==268) {
            require(command.payload.size==66)
            require(word(12) in 1..3)
            // Sparse live edits can address stage 2/3 while keeping the original header count=1.
            for(index in 0 until 3) {
                val at=14+index*10
                val function=word(at);val temperature=word(at+2);val probe=word(at+4);val minutes=word(at+6)
                require(function in 1..12 || function in 14..23 || function in 25..35 || function in 46..48 || function==UNCHANGED) { "Nieobsługiwana funkcja grzania." }
                require(function!=8 || temperature==UNCHANGED || temperature<=230) { "Zarumienienie: maksymalnie 230 °C." }
                require(function!=9 || temperature==UNCHANGED || temperature<=240) { "Przypiekanie: maksymalnie 240 °C." }
                require(function!=10 || (temperature==UNCHANGED && probe==UNCHANGED)) { "Rozmrażanie pomija nastawy temperatury i sondy." }
                require(temperature==UNCHANGED || temperature in 30..280) { "Temperatura poza zakresem." }
                require(probe==UNCHANGED || probe in 30..90) { "Temperatura sondy poza zakresem." }
                require(minutes==UNCHANGED || minutes in 1..600) { "Czas pieczenia poza zakresem." }
                if(probe!=UNCHANGED) check((client.status.value!!.sensorStatus and 16)!=0) { "Urządzenie nie potwierdziło obsługi sondy." }
            }
        }
    }
    private fun showError(message:String,connectionRelated:Boolean=false) { _error.value=AppError(message,connectionRelated,generation);log(message) }
    private fun log(message:String) {
        val safe=message.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"),"[IP]")
            .replace(settings.identity,"[CLIENT]")
            .replace(Regex("(?i)(?:password|hasło|ssid|mac|serial)\\s*[:=].*"),"[PRIVATE]")
        synchronized(logs) { logs.addLast("${System.currentTimeMillis()}: $safe");while(logs.size>200) logs.removeFirst() }
        android.util.Log.i("OPEN_OVEN",safe)
    }
}
