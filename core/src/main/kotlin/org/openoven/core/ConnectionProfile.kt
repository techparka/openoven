package org.openoven.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A private local-client profile. Never contains Wi-Fi passwords or a phone address. */
data class ConnectionProfile(val name: String, val clientIdentity: String, val tcpPort: Int = 7779,
                             val udpPort: Int = 7777, val ovenAddress: String = "") {
    fun validated(): ConnectionProfile {
        require(name.isNotBlank() && name.length <= 40 && name.none { it.isISOControl() }) { "Nazwa piekarnika powinna mieć od 1 do 40 znaków." }
        require(clientIdentity.length == 16 && clientIdentity.all { it.code in 32..126 }) { "Kod nie zawiera poprawnego profilu klienta." }
        require(tcpPort in 1..65535 && udpPort in 1..65535) { "Kod zawiera niepoprawne porty." }
        require(ovenAddress.isBlank() || (ovenAddress.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && ovenAddress.split('.').all { it.toIntOrNull() in 0..255 })) { "Kod zawiera niepoprawny adres piekarnika." }
        return this
    }
}

object ConnectionProfileCodec {
    fun encode(profile: ConnectionProfile): String = profile.validated().let {
        val values=linkedMapOf("name" to it.name,"client" to it.clientIdentity,"tcp" to it.tcpPort.toString(),"udp" to it.udpPort.toString(),"oven" to it.ovenAddress)
        "openoven://connect/v1?"+values.entries.joinToString("&") { entry -> entry.key+"="+URLEncoder.encode(entry.value,"UTF-8") }
    }
    fun decode(value: String): ConnectionProfile {
        require(value.length in 1..2048) { "To nie jest kod konfiguracji Open Oven." }
        val uri=try { URI(value) } catch(_:Exception) { throw IllegalArgumentException("To nie jest kod konfiguracji Open Oven.") }
        require(uri.scheme=="openoven" && uri.host=="connect" && uri.path=="/v1" && uri.fragment==null && uri.userInfo==null && uri.port == -1) { "Zeskanuj kod utworzony w Open Oven: Więcej → Udostępnij piekarnik." }
        val fields=linkedMapOf<String,String>()
        for(part in (uri.rawQuery ?: "").split('&')) {
            val pair=part.split('=',limit=2)
            require(pair.size==2 && pair[0] in setOf("name","client","tcp","udp","oven") && !fields.containsKey(pair[0])) { "Kod konfiguracji jest niepoprawny." }
            fields[pair[0]]=try { URLDecoder.decode(pair[1],"UTF-8") } catch(_:Exception) { throw IllegalArgumentException("Kod konfiguracji jest niepoprawny.") }
        }
        return ConnectionProfile(fields["name"].orEmpty(),fields["client"].orEmpty(),fields["tcp"]?.toIntOrNull() ?: 0,
            fields["udp"]?.toIntOrNull() ?: 0,fields["oven"].orEmpty()).validated()
    }
}
