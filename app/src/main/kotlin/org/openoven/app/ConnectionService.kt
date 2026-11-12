package org.openoven.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps an explicitly enabled appliance session visible while the screen is off. */
class ConnectionService : Service() {
    private var owner=0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==DISCONNECT) {
            AppController.get(applicationContext).disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        owner=intent?.getLongExtra("owner",owner) ?: owner
        val phase=intent?.getStringExtra("phase") ?: "Łączenie z piekarnikiem…"
        if(Build.VERSION.SDK_INT>=26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL,"Połączenie z piekarnikiem",NotificationManager.IMPORTANCE_LOW))
        }
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close=PendingIntent.getService(this,1,Intent(this,ConnectionService::class.java).setAction(DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Open Oven — połączenie lokalne")
            .setContentText(phase)
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Rozłącz",close).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(7779,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(7779,notification)
        return START_NOT_STICKY
    }
    override fun onDestroy() { AppController.existing()?.connectionServiceDestroyed(owner);super.onDestroy() }
    companion object {
        private const val CHANNEL="oven-connection"
        private const val DISCONNECT="org.openoven.app.DISCONNECT"
        fun start(context:Context,owner:Long,phase:String) { ContextCompat.startForegroundService(context,Intent(context,ConnectionService::class.java).putExtra("owner",owner).putExtra("phase",phase)) }
        fun stop(context:Context) { context.stopService(Intent(context,ConnectionService::class.java)) }
    }
}
