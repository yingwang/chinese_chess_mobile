package com.yingwang.chinesechess

import android.app.Application
import com.google.android.gms.games.PlayGamesSdk

/** Starts Play Games once per process, which also signs the player in for the leaderboard. */
class ChessApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayGamesSdk.initialize(this)
    }
}
