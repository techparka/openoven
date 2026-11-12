package org.openoven.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.delay
import java.net.Inet4Address

/** Platform boundary; the production LAN loop is also exercised with controlled ports in tests. */
interface ConnectionRuntime {
    fun localAddress():String
    fun bindWifi(address:String)
    fun clearBinding()
    fun startService(owner:Long,phase:String)
    fun stopService()
    fun nowMillis():Long
    suspend fun waitBeforeRetry(millis:Long)
}

class AndroidConnectionRuntime(private val context:Context):ConnectionRuntime {
    private val manager get()=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    override fun localAddress():String=manager.allNetworks.firstNotNullOfOrNull { network ->
        if(manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
            manager.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        else null
    }.orEmpty()
    override fun bindWifi(address:String) {
        val network=manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true &&
                manager.getLinkProperties(candidate)?.linkAddresses?.any { it.address.hostAddress==address }==true
        } ?: error("Wybrany adres nie należy do dostępnej sieci Wi-Fi.")
        check(manager.bindProcessToNetwork(network)) { "Android nie pozwolił wybrać sieci Wi-Fi dla aplikacji." }
    }
    override fun clearBinding() { manager.bindProcessToNetwork(null) }
    override fun startService(owner:Long,phase:String)=ConnectionService.start(context,owner,phase)
    override fun stopService()=ConnectionService.stop(context)
    override fun nowMillis()=SystemClock.elapsedRealtime()
    override suspend fun waitBeforeRetry(millis:Long)=delay(millis)
}
