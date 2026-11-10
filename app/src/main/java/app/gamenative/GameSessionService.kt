package app.gamenative

import android.app.Notification
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Keeps the app in the foreground process state while the game runs.
 *
 * Without it, Android treats the app as a background app as soon as the player switches away, for example to copy an
 * authenticator code, and may freeze or throttle its processes, Wine and WoW included. The Battle.net connection then
 * stops answering and the login drops with ERROR_NETWORK_MODULE_SOCKET_CLOSED. A foreground service keeps the
 * processes running, so the game stays connected while another app is in front.
 */
class GameSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            Timber.w(e, "Couldn't keep the game session in the foreground")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The player swiped the app away, which ends the game too.
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game running", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while World of Warcraft runs, so it stays connected in the background."
                    setShowBadge(false)
                },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val contentIntent = open?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_game)
            .setContentTitle("World of Warcraft is running")
            .setContentText("Stays connected while you use another app. Tap to return.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "game_session"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GameSessionService::class.java))
            }.onFailure { Timber.w(it, "Couldn't start the game session service") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GameSessionService::class.java)) }
        }
    }
}
