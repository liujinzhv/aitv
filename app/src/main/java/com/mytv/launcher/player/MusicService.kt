package com.mytv.launcher.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mytv.launcher.R
import com.mytv.launcher.ui.MusicActivity

class MusicService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val notification = buildNotification()
        return try {
            startForeground(NOTIFICATION_ID, notification)
            START_NOT_STICKY
        } catch (t: Throwable) {
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
            description = "智慧家庭TV 本地音乐播放控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val track = MusicPlayer.currentTrack
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(track?.title ?: "智慧家庭TV 音乐")
            .setContentText(track?.artist ?: "本地音乐")
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(MusicPlayer.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MusicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        track?.artworkPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { bitmap ->
                builder.setLargeIcon(bitmap)
            }
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_prev),
                "上一首",
                action(MusicActionReceiver.ACTION_PREV)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                if (MusicPlayer.isPlaying) "暂停" else "播放",
                action(MusicActionReceiver.ACTION_PLAY_PAUSE)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_next),
                "下一首",
                action(MusicActionReceiver.ACTION_NEXT)
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_close),
                "关闭",
                action(MusicActionReceiver.ACTION_STOP)
            ).build()
        )

        return builder.build()
    }

    private fun action(actionName: String): PendingIntent {
        val intent = Intent(this, MusicActionReceiver::class.java).setAction(actionName)
        return PendingIntent.getBroadcast(
            this,
            actionName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "mytv_music"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MusicService::class.java))
                running = true
            } catch (t: Throwable) {
                running = false
            }
        }

        fun update(ctx: Context) {
            if (!running) return
            start(ctx)
        }

        fun stop(ctx: Context) {
            running = false
            runCatching { ctx.stopService(Intent(ctx, MusicService::class.java)) }
        }
    }
}

