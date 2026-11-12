package org.openoven.app

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.openoven.core.CookingStage
import org.openoven.core.UNCHANGED
import java.util.UUID

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("open-oven", Context.MODE_PRIVATE)
    init {
        if (preferences.getString("identity", "").isNullOrEmpty()) {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().padEnd(16, 'a')
            val uuid = UUID.randomUUID().toString().replace("-", "").take(16)
            val mixed = uuid.take(4) + (4..15).map { mix(uuid[it], androidId[it]) }.joinToString("")
            preferences.edit().putString("identity", mixed).commit()
        }
    }
    var identity: String
        get() = preferences.getString("identity", "")!!
        set(value) { require(value.length == 16 && value.all { it.code in 32..126 }); put("identity",value) }
    var autoConnect: Boolean
        get() = preferences.getBoolean("autoConnect",false)
        set(value) { preferences.edit().putBoolean("autoConnect",value).apply() }
    var setupAwaitingHome:Boolean
        get()=preferences.getBoolean("setupAwaitingHome",false)
        set(value) { preferences.edit().putBoolean("setupAwaitingHome",value).commit() }
    var ovenName: String
        get() = preferences.getString("ovenName","Piekarnik w kuchni")!!
        set(value) { require(value.isNotBlank() && value.length<=40);put("ovenName",value.trim()) }
    var ipAddress: String
        get() = preferences.getString("ip", "")!!
        set(value) { put("ip",value.trim()) }
    var tcpPort: Int
        get() = preferences.getInt("tcp",7779)
        set(value) { require(value in 1..65535); preferences.edit().putInt("tcp",value).apply() }
    var udpPort: Int
        get() = preferences.getInt("udp",7777)
        set(value) { require(value in 1..65535); preferences.edit().putInt("udp",value).apply() }
    var broadcastAddress: String
        get() = preferences.getString("broadcast", "")!!
        set(value) { put("broadcast",value.trim()) }
    var deviceAddress: String
        get() = preferences.getString("device", "")!!
        set(value) { put("device",value.trim()) }
    var darkTheme: Boolean
        get() = themeMode==2
        set(value) { preferences.edit().putBoolean("dark",value).putInt("themeMode",if(value)2 else 1).apply() }
    var themeMode:Int
        get() = preferences.getInt("themeMode",if(preferences.contains("dark")) { if(preferences.getBoolean("dark",false))2 else 1 } else -1)
        set(value) { require(value in setOf(-1,1,2));preferences.edit().putInt("themeMode",value).apply() }
    fun history(): List<List<CookingStage>> = runCatching {
        val histories=JSONArray(preferences.getString("history","[]"))
        (0 until histories.length()).map { h ->
            val stages=histories.getJSONArray(h)
            (0 until stages.length()).map { i -> stages.getJSONObject(i).let {
                CookingStage(it.getInt("function"),it.getInt("temperature"),it.optInt("probe",UNCHANGED),it.getInt("minutes"))
            } }
        }.take(5)
    }.getOrDefault(emptyList())
    fun saveHistory(values: List<List<CookingStage>>) {
        val array=JSONArray()
        values.take(5).forEach { stages ->
            require(stages.size in 1..3)
            array.put(JSONArray().apply { stages.forEach { put(JSONObject().put("function",it.function).put("temperature",it.temperature).put("probe",it.probeTemperature).put("minutes",it.durationMinutes)) } })
        }
        put("history",array.toString())
    }
    private fun put(key:String,value:String) { preferences.edit().putString(key,value).apply() }
    private fun mix(left:Char,right:Char):Char {
        var value=left.code xor right.code
        if(value==0) value=left.code or right.code
        while(value<48) value*=2
        while(value>122) value/=2
        if(value>57 && value<65) value+=8
        if(value>90 && value<97) value+=9
        return value.toChar()
    }
}
