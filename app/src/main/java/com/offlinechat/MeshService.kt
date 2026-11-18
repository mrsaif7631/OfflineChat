package com.offlinechat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the mesh running when the app is in the background or the screen is off,
 * so you still receive messages and still relay messages for other people.
 */
class MeshService : Service() {

    companion object {
        private const val ACTION_STOP = "com.offlinechat.STOP"
        private const val EXTRA_CODE = "code"

        var engine: MeshEngine? = null
            private set

        fun start(ctx: Context, roomCode: String) {
            val i = Intent(ctx, MeshService::class.java).putExtra(EXTRA_CODE, roomCode)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MeshService::class.java).setAction(ACTION_STOP))
        }

        fun stopIntent(ctx: Context): PendingIntent = PendingIntent.getService(
            ctx, 1, Intent(ctx, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        val code = intent?.getStringExtra(EXTRA_CODE) ?: return START_NOT_STICKY

        ServiceCompat.startForeground(
            this, Notifier.SERVICE_ID, Notifier.serviceNotification(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )

        if (engine?.roomCode != code) {
            engine?.stop()
            engine = MeshEngine(this, code).also { it.start() }
        }
        Repo.activeRoomCode = code
        return START_NOT_STICKY
    }

    private fun shutdown() {
        engine?.stop()
        engine = null
        Repo.activeRoomCode = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        Repo.activeRoomCode = null
        super.onDestroy()
    }
}

object Notifier {
    private const val CH_MESSAGES = "messages"
    private const val CH_SERVICE = "mesh"
    const val SERVICE_ID = 1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Offline network running", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Offline Chat is on")
            .setContentText("Receiving and relaying messages from nearby phones")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Turn off", MeshService.stopIntent(ctx))
            .build()

    fun showMessage(ctx: Context, m: Msg, roomCode: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = if (m.isPrivate) "🔒 ${m.senderName}" else "${m.senderName} · $roomCode"
        val text = when (m.kind) {
            Kind.TEXT -> m.text
            Kind.IMAGE -> "📷 Photo"
            Kind.VOICE -> "🎤 Voice message"
        }
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify("${m.roomId}/${m.conv}".hashCode(), n)
        } catch (_: SecurityException) {}
    }
}
