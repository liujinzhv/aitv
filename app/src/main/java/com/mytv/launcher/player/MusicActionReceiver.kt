package com.mytv.launcher.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MusicActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV -> MusicPlayer.previous(context)
            ACTION_NEXT -> MusicPlayer.next(context)
            ACTION_PLAY_PAUSE -> MusicPlayer.toggle(context)
            ACTION_STOP -> MusicPlayer.stop(context)
        }
    }

    companion object {
        const val ACTION_PREV = "com.mytv.launcher.music.PREV"
        const val ACTION_NEXT = "com.mytv.launcher.music.NEXT"
        const val ACTION_PLAY_PAUSE = "com.mytv.launcher.music.PLAY_PAUSE"
        const val ACTION_STOP = "com.mytv.launcher.music.STOP"
    }
}
