package org.openoven.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns only its explicitly requested SmartIN network; never disconnects saved home Wi-Fi. */
class SetupWifiNetwork(context:Context,private val onAccessPointLost:()->Unit={}) {
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Volatile private var callback:ConnectivityManager.NetworkCallback?=null
    @Volatile private var accessPoint:Network?=null
    private fun address(network:Network)=manager.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    @Suppress("DEPRECATION")
    private fun name(network:Network):String? {
        val info=if(Build.VERSION.SDK_INT>=29)manager.getNetworkCapabilities(network)?.transportInfo as? WifiInfo else null
        fun visible(value:String?)=value?.trim('"')?.takeUnless { it==WifiManager.UNKNOWN_SSID || it.isBlank() }
        visible(info?.ssid)?.let { return it }
        // Some Android versions redact transportInfo even when connectionInfo is available.
        // Match the primary Wi-Fi IP before using its name; never label a second network with it.
        val primary=wifi.connectionInfo ?: return null
        val ip=(0..3).joinToString(".") { ((primary.ipAddress ushr (it*8)) and 255).toString() }
        return if(address(network)==ip)visible(primary.ssid) else null
    }
    private fun networks()=manager.allNetworks.filter {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true && address(it)!=null
    }
    suspend fun connectSmartIn():String {
        release()
        networks().firstOrNull { name(it)=="SmartIN" }?.let { network ->
            val owned=object:ConnectivityManager.NetworkCallback() {
                override fun onLost(lost:Network) {
                    synchronized(this@SetupWifiNetwork) {
                        if(callback!==this || accessPoint!=lost) return
                        accessPoint=null
                    }
                    onAccessPointLost()
                }
            }
            callback=owned;accessPoint=network
            try { manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),owned) }
            catch(e:Exception) { release();throw e }
            check(manager.bindProcessToNetwork(network)) { "Nie można wybrać sieci SmartIN." }
            return address(network)!!
        }
        if(Build.VERSION.SDK_INT<29) throw IllegalStateException("W ustawieniach Wi-Fi telefonu wybierz SmartIN i wróć do kreatora.")
        return requestSmartIn()
    }
    @androidx.annotation.RequiresApi(29)
    private suspend fun requestSmartIn():String {
        return withTimeoutOrNull(65000) {
            suspendCancellableCoroutine { continuation ->
                val owned=object:ConnectivityManager.NetworkCallback() {
                    private fun ready(network:Network,properties:LinkProperties?) {
                        val ip=properties?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: return
                        synchronized(this@SetupWifiNetwork) {
                            if(callback!==this || !continuation.isActive) return
                            if(!manager.bindProcessToNetwork(network)) {
                                continuation.resumeWithException(IllegalStateException("Android nie pozwolił wybrać SmartIN."));return
                            }
                            accessPoint=network;continuation.resume(ip)
                        }
                    }
                    override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties) { ready(network,properties) }
                    override fun onUnavailable() {
                        if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Połączenie ze SmartIN odrzucone lub niedostępne."))
                    }
                    override fun onLost(network:Network) {
                        synchronized(this@SetupWifiNetwork) {
                            if(callback!==this || accessPoint!=network) return
                            accessPoint=null
                            if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Utracono SmartIN."))
                        }
                        onAccessPointLost()
                    }
                }
                callback=owned
                continuation.invokeOnCancellation { release() }
                try {
                    val specifier=WifiNetworkSpecifier.Builder().setSsid("SmartIN").setWpa2Passphrase("12345678").build()
                    val request=NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
                    manager.requestNetwork(request,owned)
                } catch(e:Exception) { if(continuation.isActive)continuation.resumeWithException(e) }
            }
        } ?: error("Nie znaleziono SmartIN w wyznaczonym czasie.")
    }
    fun currentAccessPointAddress():String?=accessPoint?.let { network ->
        if(manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)address(network) else null
    }
    fun homeAddress(expectedSsid:String?=null):String {
        val candidates=networks().filter { it!=accessPoint && name(it)?.let { ssid->ssid!="SmartIN" }==true }
        val network=candidates.firstOrNull { expectedSsid==null || name(it)==expectedSsid }
            ?: error("Połącz telefon z domową siecią Wi-Fi, nie SmartIN, i wróć do aplikacji.")
        check(manager.bindProcessToNetwork(network)) { "Android nie pozwolił wybrać domowego Wi-Fi." }
        return address(network)!!
    }
    fun homeNetworkAvailable(expectedSsid:String)=networks().any { name(it)==expectedSsid }
    @Synchronized fun release() {
        val owned=callback;callback=null;accessPoint=null
        if(owned!=null) runCatching { manager.unregisterNetworkCallback(owned) }
        manager.bindProcessToNetwork(null)
    }
}
