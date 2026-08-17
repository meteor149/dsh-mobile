package ai.meteor.dshmobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.meteor.dshmobile.MainActivity
import ai.meteor.dshmobile.R
import ai.meteor.dshmobile.runtime.RuntimePhase.Running
import kotlinx.coroutines.launch

class RuntimeService : LifecycleService() {
    private val manager by lazy { RuntimeManager.get(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_START
        startForeground(
            NOTIFICATION_ID,
            notification(
                if (action == ACTION_INSTALL) R.string.notification_installing else R.string.notification_starting,
            ),
        )
        lifecycleScope.launch {
            when (action) {
                ACTION_INSTALL -> {
                    manager.install()
                }
                ACTION_START -> manager.start()
                ACTION_STOP -> manager.stop()
            }
            if (RuntimeStateStore.state.value.phase == Running) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(R.string.notification_running))
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun notification(@StringRes message: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(message))
        .setOngoing(RuntimeStateStore.state.value.phase == Running)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            getString(R.string.notification_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_INSTALL = "ai.meteor.dshmobile.action.INSTALL"
        const val ACTION_START = "ai.meteor.dshmobile.action.START"
        const val ACTION_STOP = "ai.meteor.dshmobile.action.STOP"
        private const val CHANNEL_ID = "dsh-runtime"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, action: String): Intent =
            Intent(context, RuntimeService::class.java).setAction(action)
    }
}
