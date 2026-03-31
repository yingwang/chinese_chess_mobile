package com.yingwang.chinesechess

import android.content.Context
import com.yingwang.chinesechess.model.PieceColor
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Simple ELO-like rating system for tracking player strength.
 */
object RatingSystem {

    private const val PREFS_NAME = "chess_rating"
    private const val KEY_RATING = "player_rating"
    private const val KEY_GAMES = "total_games"
    private const val KEY_WINS = "total_wins"
    private const val KEY_LOSSES = "total_losses"
    private const val KEY_DRAWS = "total_draws"
    private const val INITIAL_RATING = 1200

    // AI ratings by difficulty
    private val AI_RATINGS = mapOf(
        GameController.AIDifficulty.BEGINNER to 800,
        GameController.AIDifficulty.INTERMEDIATE to 1000,
        GameController.AIDifficulty.ADVANCED to 1300,
        GameController.AIDifficulty.PROFESSIONAL to 1600,
        GameController.AIDifficulty.MASTER to 1900
    )

    data class PlayerStats(
        val rating: Int,
        val games: Int,
        val wins: Int,
        val losses: Int,
        val draws: Int
    ) {
        val winRate: String
            get() = if (games > 0) "${(wins * 100.0 / games).roundToInt()}%" else "—"

        val rankTitle: String
            get() = when {
                rating >= 2000 -> "大师"
                rating >= 1700 -> "专家"
                rating >= 1400 -> "高手"
                rating >= 1100 -> "棋友"
                rating >= 800 -> "新手"
                else -> "初学"
            }
    }

    fun getStats(context: Context): PlayerStats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PlayerStats(
            rating = prefs.getInt(KEY_RATING, INITIAL_RATING),
            games = prefs.getInt(KEY_GAMES, 0),
            wins = prefs.getInt(KEY_WINS, 0),
            losses = prefs.getInt(KEY_LOSSES, 0),
            draws = prefs.getInt(KEY_DRAWS, 0)
        )
    }

    /**
     * Update rating after a game.
     * @param result 1.0 = win, 0.0 = loss, 0.5 = draw
     */
    fun recordGame(context: Context, difficulty: GameController.AIDifficulty, result: Double): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentRating = prefs.getInt(KEY_RATING, INITIAL_RATING)
        val games = prefs.getInt(KEY_GAMES, 0)
        val aiRating = AI_RATINGS[difficulty] ?: 1200

        // ELO calculation
        val k = if (games < 20) 40 else 20 // higher K for new players
        val expected = 1.0 / (1.0 + Math.pow(10.0, (aiRating - currentRating) / 400.0))
        val newRating = (currentRating + k * (result - expected)).roundToInt().coerceAtLeast(100)
        val ratingChange = newRating - currentRating

        prefs.edit()
            .putInt(KEY_RATING, newRating)
            .putInt(KEY_GAMES, games + 1)
            .putInt(KEY_WINS, prefs.getInt(KEY_WINS, 0) + if (result == 1.0) 1 else 0)
            .putInt(KEY_LOSSES, prefs.getInt(KEY_LOSSES, 0) + if (result == 0.0) 1 else 0)
            .putInt(KEY_DRAWS, prefs.getInt(KEY_DRAWS, 0) + if (result == 0.5) 1 else 0)
            .apply()

        return ratingChange
    }
}
