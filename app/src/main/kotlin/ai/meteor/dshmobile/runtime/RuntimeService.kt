package ai.meteor.dshmobile.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.meteor.dshmobile.MainActivity
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
        startForeground(NOTIFICATION_ID, notification(if (action == ACTION_INSTALL) "正在安装运行时" else "运行时正在启动"))
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
                    .notify(NOTIFICATION_ID, notification("DeepSeek Harness 正在运行"))
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun notification(message: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("DSH Mobile")
        .setContentText(message)
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
            "停止",
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
            "本地运行时",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 Ubuntu 和 DeepSeek Harness 的运行状态"
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
