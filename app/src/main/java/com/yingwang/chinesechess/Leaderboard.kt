package com.yingwang.chinesechess

import android.app.Activity
import android.widget.Toast
import com.google.android.gms.games.PlayGames

/**
 * The rating leaderboard on Google Play Games: the challenge rating kept by [RatingSystem],
 * posted after each rated game and whenever the app comes back, and the board itself opened
 * from the menu.
 *
 * Play Games signs the player in on its own when the app starts ([ChessApp]); without a network
 * or a Play Games profile that simply fails and the game carries on, and the rating goes up the
 * next time it can. Someone who declined is asked again only when they open the board. Nothing
 * is posted before the first rated game, so the board is not filled with everyone's starting
 * 1200, and as Play keeps each player's best score, the board ranks the highest rating reached.
 */
object Leaderboard {
    private const val SHOW_REQUEST = 9004

    /** Posts the current rating if Play Games has the player signed in. */
    fun post(activity: Activity) {
        val stats = RatingSystem.getStats(activity)
        if (stats.games == 0) return
        PlayGames.getGamesSignInClient(activity).isAuthenticated.addOnCompleteListener { task ->
            if (task.isSuccessful && task.result.isAuthenticated) submit(activity, stats.rating)
        }
    }

    /** Signs in if need be, posts the rating, and opens the board. */
    fun show(activity: Activity) {
        val signIn = PlayGames.getGamesSignInClient(activity)
        signIn.isAuthenticated.continueWithTask { task ->
            if (task.isSuccessful && task.result.isAuthenticated) task else signIn.signIn()
        }.addOnCompleteListener { task ->
            if (!task.isSuccessful || !task.result.isAuthenticated) {
                Toast.makeText(activity, R.string.leaderboard_sign_in_failed, Toast.LENGTH_SHORT).show()
                return@addOnCompleteListener
            }
            val stats = RatingSystem.getStats(activity)
            if (stats.games > 0) submit(activity, stats.rating)
            PlayGames.getLeaderboardsClient(activity)
                .getLeaderboardIntent(activity.getString(R.string.leaderboard_rating))
                .addOnSuccessListener { intent ->
                    // Play Games reads the calling activity to know which game's board to show.
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, SHOW_REQUEST)
                }
                .addOnFailureListener {
                    Toast.makeText(activity, R.string.leaderboard_unavailable, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun submit(activity: Activity, rating: Int) {
        PlayGames.getLeaderboardsClient(activity)
            .submitScore(activity.getString(R.string.leaderboard_rating), rating.toLong())
    }
}
