package org.openoven.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.net.*
import kotlin.coroutines.coroutineContext

/** Client is a TCP listener: the appliance connects back after a UDP beacon. */
class LanSocketClient(private val scope: CoroutineScope, private val log: (String) -> Unit = {},
                      private val simulationOptions: SimulationOptions = SimulationOptions()) : OvenClient {
    private val state = MutableStateFlow(ConnectionInfo(ConnectionState.DISCONNECTED))
    private val device = MutableStateFlow<OvenIdentity?>(null)
    private val current = MutableStateFlow<OvenStatus?>(null)
    override val connection: StateFlow<ConnectionInfo> = state
    override val identity: StateFlow<OvenIdentity?> = device
    override val status: StateFlow<OvenStatus?> = current
    private val requests = Mutex()
    private val socketOperations=SocketOperation(scope)
    private val lifecycle = Mutex()
    @Volatile private var socket: Socket? = null
    @Volatile private var listener: ServerSocket? = null
    private var poll: Job? = null
    private var demoOven: SimulatedOven? = null
    private var settings: LanSettings? = null
    private var sequence = 0
    private var sessionKey = 0
    @Volatile private var sessionGeneration = 0
    private val resources = Any()
    private var authenticationPort = 7779

    override suspend fun connect(settings: LanSettings) = connectSession(settings)

    suspend fun connectDemo(settings:LanSettings,options:SimulationOptions) {
        require(settings.demo) { "Scenariusze symulatora wymagają demonstracji." }
        connectSession(settings,demoOptions=options)
    }

    /** AP setup ends after its acknowledgement; oven identity/status are not prerequisites. */
    suspend fun provisionTcp(settings: LanSettings, ssid: String, password: String) =
        connectSession(settings, OvenCommands.provision(ssid, password))

    private suspend fun connectSession(settings: LanSettings, configuration: OvenCommand? = null, demoOptions:SimulationOptions=simulationOptions) = lifecycle.withLock {
        disconnect()
        require(settings.clientIdentity.length == 16 && settings.clientIdentity.all { it.code in 32..126 }) { "Tożsamość klienta musi mieć 16 znaków ASCII." }
        require(settings.tcpPort in 0..65535 && settings.udpPort in 1..65535)
        require(settings.timeoutMillis in 500..30000)
        this.settings = settings
        sequence = 0
        sessionKey = 0
        device.value = null
        current.value = null
        val generation = ++sessionGeneration
        try {
            withContext(Dispatchers.IO) {
                val local = InetAddress.getByName(settings.localAddress)
                require(local is Inet4Address) { "Wybierz adres IPv4 sieci Wi-Fi." }
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(local, settings.tcpPort))
                    soTimeout = 500
                }
                synchronized(resources) {
                    if (generation != sessionGeneration) { server.close(); throw CancellationException("Połączenie anulowane") }
                    listener = server
                    authenticationPort = server.localPort
                }
                state.value = ConnectionInfo(ConnectionState.LISTENING, "Oczekiwanie na piekarnik", settings.demo)
                log("Listener gotowy; tryb=${if (settings.demo) "demo" else "LAN"}")
                if (settings.demo) {
                    require(local.isLoopbackAddress) { "Demo wymaga adresu loopback" }
                    demoOven = SimulatedOven(scope, demoOptions).also { it.connect(local.hostAddress!!, server.localPort, server.localPort) }
                }
                val deadline = SocketDeadline(settings.timeoutMillis * 8L)
                var lastBeacon:Long? = null
                // Open Oven starts at 1; APK init sets startBc=1 and ++startBc sends 2 first.
                // Counter=1 worked in the recorded hardware session; exact start requirement is unknown.
                var counter = 0
                while (socket == null && !deadline.expired()) {
                    coroutineContext.ensureActive()
                    if (!settings.demo && (lastBeacon==null || System.nanoTime()-lastBeacon!!>=1_000_000_000L)) {
                        sendBeacon(settings, server.localPort, ++counter)
                        lastBeacon = System.nanoTime()
                    }
                    try {
                        val accepted = server.accept()
                        if (settings.expectedDeviceAddress.isNullOrBlank() || accepted.inetAddress.hostAddress == settings.expectedDeviceAddress) {
                            synchronized(resources) {
                                if (generation != sessionGeneration) { accepted.close(); throw CancellationException("Połączenie anulowane") }
                                socket = accepted.apply { soTimeout = settings.timeoutMillis; tcpNoDelay = true }
                            }
                        } else {
                            accepted.close()
                            log("Pominięto połączenie od innego urządzenia")
                        }
                    } catch (_: SocketTimeoutException) { }
                }
                server.close()
                listener = null
                if (socket == null) throw SocketTimeoutException("Piekarnik nie odpowiedział na wykrywanie.")
                if (generation != sessionGeneration) throw CancellationException("Połączenie anulowane")
                log("TCP: przyjęto połączenie urządzenia")
                state.value = ConnectionInfo(ConnectionState.AUTHENTICATING, "Uwierzytelnianie", settings.demo)
                authenticate(generation)
                if (configuration != null) {
                    log("TCP: wysyłanie konfiguracji Wi-Fi po handshake")
                    terminalConfiguration(configuration, generation)
                    log("TCP: potwierdzono konfigurację Wi-Fi (147)")
                    finishSession(generation)
                    return@withContext
                }
                val identity = StatusCodec.identity(request(0, 258, 100, 26, generation = generation).payload)
                val status = StatusCodec.status(request(0, 258, 128, 77, generation = generation).payload).copy(observedAtMillis = System.currentTimeMillis())
                synchronized(resources) {
                    if (generation != sessionGeneration) throw CancellationException("Połączenie anulowane")
                    device.value = identity
                    current.value = status
                    state.value = ConnectionInfo(ConnectionState.CONNECTED, "Połączono", settings.demo)
                }
                log("Handshake i odczyt statusu zakończone")
            }
            if (configuration == null) poll = scope.launch {
                while (isActive && generation == sessionGeneration) {
                    delay(1500)
                    try { refresh(generation) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { fail(e, generation); break }
                }
            }
        } catch (e: CancellationException) { finishSession(generation); throw e }
        catch (e: Exception) {
            if (generation != sessionGeneration) throw CancellationException("Połączenie anulowane", e)
            fail(e, generation); throw e
        }
    }

    private suspend fun authenticate(generation: Int) {
        log("TCP: odczyt identyfikacji modemu")
        val modem = request(0, 1, 92, 12, key = 0, generation = generation).payload
        require(modem.size >= 8) { "Niepełna identyfikacja modemu" }
        val random = HandshakeKeys.sessionBytes()
        request(1, 1, 128, 16, random, key = 0, responseOpcode = 3, generation = generation)
        sessionKey = random[0].toInt()
        val actual = request(0, 1, 144, 16, random, generation = generation).payload
        val expected = HandshakeKeys.encryptBlock16(random, HandshakeKeys.modemAesKey(modem.copyOf(8)))
        if (!java.security.MessageDigest.isEqual(actual, expected)) throw IOException("Niezgodna odpowiedź uwierzytelnienia AES")
        log("TCP: zweryfikowano dowód AES urządzenia")
        val challenge = request(0, 1, 128, 16, generation = generation).payload
        val proof = HandshakeKeys.encryptBlock16(challenge, HandshakeKeys.portAesKey(authenticationPort))
        request(1, 1, 144, 16, proof, responseOpcode = 3, generation = generation)
        log("TCP: zakończono handshake AES")
    }

    private suspend fun terminalConfiguration(command:OvenCommand,generation:Int) {
        val key=sessionKey
        var seq=0
        socketOperations.run((settings?.timeoutMillis ?:4000).toLong(),requests,
            socket={ requestLease(generation).let { seq=it.second;it.first } },abort={ abortRequest(generation,it) }) { sock,deadline ->
            val bytes=FrameCodec.encode(ProtocolFrame(seq,command.opcode,command.group,command.address,command.payload.size,command.payload),key)
            sock.getOutputStream().apply { write(bytes);flush() }
            val acknowledgement=FrameCodec.decodeHeader(readExactly(sock,11,deadline),key)
            if(acknowledgement.sequence!=seq)throw IOException("Niepasujący numer potwierdzenia konfiguracji Wi-Fi")
            if(acknowledgement.opcode!=147)throw IOException("Urządzenie nie potwierdziło konfiguracji Wi-Fi (147)")
        }
    }
    private fun requestLease(generation:Int):Pair<Socket,Int> = synchronized(resources) {
        if(generation!=sessionGeneration)throw CancellationException("Nieaktualna sesja")
        val owned=socket ?: throw IOException("Połączenie jest zamknięte")
        sequence=if(sequence>=65000)0 else sequence+1
        owned to sequence
    }
    private fun abortRequest(generation:Int,owned:Socket)=synchronized(resources) {
        if(generation==sessionGeneration && socket===owned) {
            closeResources()
            state.value=ConnectionInfo(ConnectionState.ERROR,"Operacja przerwana; połączenie wymaga odtworzenia",settings?.demo ?:false)
        } else runCatching { owned.close() }
    }
    private fun finishSession(generation: Int) = synchronized(resources) {
        if (generation != sessionGeneration) return@synchronized
        ++sessionGeneration
        closeResources()
        sessionKey = 0
        sequence = 0
        device.value = null
        current.value = null
        state.value = ConnectionInfo(ConnectionState.DISCONNECTED, "Rozłączono")
    }

    override suspend fun refresh() {
        val generation=sessionGeneration
        // A UI refresh while listening must not tear down an in-progress handshake.
        check(state.value.state==ConnectionState.CONNECTED) { "Brak połączenia" }
        try { refresh(generation) }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(generation!=sessionGeneration) throw CancellationException("Nieaktualna sesja",e)
            fail(e,generation);throw e
        }
    }

    private suspend fun refresh(generation: Int) {
        check(state.value.state == ConnectionState.CONNECTED) { "Brak połączenia" }
        val old = current.value
        val next = StatusCodec.status(request(0, 258, 128, 77, generation = generation).payload, old).copy(observedAtMillis = System.currentTimeMillis())
        synchronized(resources) { if (generation == sessionGeneration) current.value = next }
    }

    override suspend fun send(command: OvenCommand) {
        val generation = sessionGeneration
        check(state.value.state == ConnectionState.CONNECTED) { "Brak połączenia" }
        try {
            request(command.opcode, command.group, command.address, command.payload.size, command.payload, responseOpcode = command.responseOpcode, generation = generation)
            // Confirmation is not a retry. An uncertain write is never sent twice automatically.
            refresh(generation)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(e, generation); throw e }
    }

    internal suspend fun request(opcode:Int,group:Int,address:Int,length:Int,
        body:ByteArray=byteArrayOf(),key:Int=sessionKey,responseOpcode:Int=2,generation:Int=sessionGeneration):ProtocolFrame {
        var seq=0
        return socketOperations.run((settings?.timeoutMillis ?:4000).toLong(),requests,
            socket={ requestLease(generation).let { seq=it.second;it.first } },abort={ abortRequest(generation,it) }) { sock,deadline ->
            val encoded=FrameCodec.encode(ProtocolFrame(seq,opcode,group,address,length,body),key)
            sock.getOutputStream().apply { write(encoded);flush() }
            var answer:ProtocolFrame?=null
            for(attempt in 0 until 8) {
                sock.soTimeout=deadline.remainingMillis()
                val header=readExactly(sock,11,deadline)
                val meta=FrameCodec.decodeHeader(header,key)
                require(meta.declaredLength in 0..4096) { "Nieprawidłowy rozmiar odpowiedzi" }
                val frame=FrameCodec.decodeResponse(header+readExactly(sock,meta.declaredLength,deadline),key)
                if(frame.sequence==seq) {
                    if(frame.opcode!=responseOpcode || frame.group!=group || frame.address!=address)throw IOException("Odpowiedź nie odpowiada żądaniu")
                    if(frame.declaredLength!=length)throw IOException("Nieoczekiwana długość odpowiedzi")
                    log("Odpowiedź: op=${frame.opcode}, grupa=$group, adres=$address, długość=${frame.payload.size}")
                    answer=frame;break
                }
                log("Pominięto opóźnioną odpowiedź")
            }
            answer ?: throw IOException("Zbyt wiele niepasujących odpowiedzi")
        }

    }

    private fun readExactly(socket:Socket,count:Int,deadline:SocketDeadline):ByteArray {
        val result=ByteArray(count)
        var offset=0
        while(offset<count) {
            socket.soTimeout=deadline.remainingMillis()
            val size=socket.getInputStream().read(result,offset,count-offset)
            if(size<0)throw EOFException("Piekarnik zamknął połączenie")
            offset+=size
        }
        return result
    }

    private fun sendBeacon(config: LanSettings, port: Int, counter: Int) {
        val local = InetAddress.getByName(config.localAddress)
        val destination = config.broadcastAddress?.takeIf { it.isNotBlank() }
            ?: local.address.copyOf().also { it[3] = 0xff.toByte() }.let { InetAddress.getByAddress(it).hostAddress!! }
        DatagramSocket(InetSocketAddress(local, 0)).use { udp ->
            udp.broadcast = true
            val bytes = DiscoveryCodec.beacon(config.clientIdentity, config.localAddress, port, counter)
            udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(destination), config.udpPort))
        }
        log("Wysłano wykrywanie UDP")
    }

    suspend fun provisionUdp(config: LanSettings, ssid: String, password: String) = withContext(Dispatchers.IO) {
        val packet = DiscoveryCodec.provision(config.clientIdentity, ssid, password)
        val address = config.broadcastAddress?.takeIf { it.isNotBlank() }
            ?: InetAddress.getByName(config.localAddress).address.copyOf().also { it[3] = 0xff.toByte() }
                .let { InetAddress.getByAddress(it).hostAddress!! }
        DatagramSocket(InetSocketAddress(config.localAddress, 0)).use { udp ->
            udp.broadcast = true
            repeat(5) { udp.send(DatagramPacket(packet, packet.size, InetAddress.getByName(address), config.udpPort)); delay(300) }
        }
        log("Wysłano konfigurację Wi-Fi; potwierdzenie wymaga ponownego połączenia")
    }

    override suspend fun disconnect() = synchronized(resources) {
        ++sessionGeneration
        poll?.cancel(); poll = null
        closeResources()
        sessionKey = 0
        sequence = 0
        state.value = ConnectionInfo(ConnectionState.DISCONNECTED, "Rozłączono")
        device.value = null
        current.value = null
    }

    private fun closeResources() {
        runCatching { listener?.close() }; listener = null
        runCatching { socket?.close() }; socket = null
        demoOven?.close(); demoOven = null
    }
    private fun fail(error: Exception, generation: Int) = synchronized(resources) {
        if (generation != sessionGeneration) return@synchronized
        closeResources()
        state.value = ConnectionInfo(ConnectionState.ERROR, error.message ?: "Błąd połączenia", settings?.demo ?: false)
        log("Błąd komunikacji: ${error.javaClass.simpleName}")
    }
}
