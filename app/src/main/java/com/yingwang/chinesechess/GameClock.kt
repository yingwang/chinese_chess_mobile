package com.yingwang.chinesechess

import com.yingwang.chinesechess.model.PieceColor
import org.json.JSONObject
import java.util.Locale

/**
 * Each side's thinking time, as on a two-sided clock on the table. No time limits: it only counts.
 *
 * Only the side on the move ([turn]) is charged, and only while the clock is not [paused] (the app
 * is in the foreground). Time comes from [now], a monotonic millisecond source
 * (SystemClock.elapsedRealtime in the app), never the wall clock, so changing the phone's time
 * cannot move it. Whenever the turn or the pause changes, the time since the last change is
 * banked to the side that was on the move, so every interval is charged once, to one side, and a
 * clock picks up after a pause exactly where it stopped.
 *
 * A null [turn] stops both clocks: the game is over, or a replay is on the board.
 *
 * Taking a move back (悔棋) rewinds the position, not the clocks, as on a real table: the time
 * each side spent stays on its clock, and the side to move after the take-back is charged from
 * then on. The clocks only ever grow, so nothing is given back, once or twice, and nothing is
 * counted twice; the two together are the time the game has been played in the foreground.
 */
class GameClock(private val now: () -> Long) {
    private var redMs = 0L
    private var blackMs = 0L
    /** When the time not yet banked began to run. */
    private var since = now()

    /** The side whose clock runs while the app is in the foreground; null when both are stopped. */
    var turn: PieceColor? = null
        private set

    /** True while the app is in the background: neither clock runs. */
    var paused = false
        private set

    /** The side being charged at this moment, if any. */
    val running: PieceColor? get() = if (paused) null else turn

    /** Time [side] has used, including the run in progress. */
    fun elapsed(side: PieceColor): Long {
        val banked = if (side == PieceColor.RED) redMs else blackMs
        return if (running == side) banked + (now() - since).coerceAtLeast(0L) else banked
    }

    /** Both sides together: the time the game has been played. */
    fun total(): Long = elapsed(PieceColor.RED) + elapsed(PieceColor.BLACK)

    /** Sets both clocks, for a new game or a restored one, with [turn] on the move. */
    fun reset(redMs: Long = 0L, blackMs: Long = 0L, turn: PieceColor? = null) {
        this.redMs = redMs.coerceAtLeast(0L)
        this.blackMs = blackMs.coerceAtLeast(0L)
        this.turn = turn
        since = now()
    }

    /** Hands the move to [side], or stops both clocks for null. */
    fun setTurn(side: PieceColor?) {
        if (side == turn) return
        bank()
        turn = side
    }

    fun setPaused(paused: Boolean) {
        if (paused == this.paused) return
        bank()
        this.paused = paused
    }

    /** Charges the time since the last change to the side that was running, and starts afresh. */
    private fun bank() {
        val t = now()
        when (running) {
            PieceColor.RED -> redMs += (t - since).coerceAtLeast(0L)
            PieceColor.BLACK -> blackMs += (t - since).coerceAtLeast(0L)
            null -> {}
        }
        since = t
    }

    /** Puts both clocks into a saved game. */
    fun writeTo(json: JSONObject) {
        json.put(KEY_RED, elapsed(PieceColor.RED))
        json.put(KEY_BLACK, elapsed(PieceColor.BLACK))
    }

    companion object {
        const val KEY_RED = "redClockMs"
        const val KEY_BLACK = "blackClockMs"

        /**
         * Red's and black's time in a saved game. A save from before 2.4.8 has only one total,
         * taken from the wall clock and swollen by any time the app spent in the background, with
         * nothing to say which side used it; both clocks start from zero for it.
         */
        fun savedTimes(json: JSONObject): Pair<Long, Long> =
            json.optLong(KEY_RED, 0L).coerceAtLeast(0L) to json.optLong(KEY_BLACK, 0L).coerceAtLeast(0L)

        /** 05:07 under an hour, 1:05:07 from an hour on. */
        fun format(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            else String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
