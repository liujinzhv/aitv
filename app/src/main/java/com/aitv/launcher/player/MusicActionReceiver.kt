package com.aitv.launcher.player

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
        const val ACTION_PREV = "com.aitv.launcher.music.PREV"
        const val ACTION_NEXT = "com.aitv.launcher.music.NEXT"
        const val ACTION_PLAY_PAUSE = "com.aitv.launcher.music.PLAY_PAUSE"
        const val ACTION_STOP = "com.aitv.launcher.music.STOP"
    }
}
