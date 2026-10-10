package com.yingwang.chinesechess.online

import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.Position
import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * What the app and the web version (yingwang.github.io/chinese_chess, js/online.js) write to and
 * read from the Realtime Database, kept free of Firebase and Android so it can be tested on the JVM.
 *
 * A room lives at `games/{code}`:
 *
 *     meta     { createdAt, status: waiting|playing|finished, gameCode, hostColor, startedAt }
 *     players  { red: { uid, connected }, black: { uid, connected } }
 *     moves    { 0: { fromRow, fromCol, toRow, toCol, t }, 1: {...}, ... }
 *     result   { type: checkmate|stalemate|perpetualCheck|repetition|resign, winner: red|black, t }
 *
 * Squares are the same on both sides and need no translation: row 0 is black's back rank and
 * row 9 red's, column 0 is the left edge with red at the bottom ([Position], and Position in the
 * web's model.js). A player on black sees the board turned round, but that is only drawing
 * (BoardView.flipped); what goes over the wire is always this orientation.
 *
 * Move n is red's when n is even. `t` is the server's clock when the move was written
 * (ServerValue.TIMESTAMP); both sides compute each side's thinking time from these, so the
 * clocks agree across devices. Moves written by an older web page have no `t`.
 */
object OnlineProtocol {
    /** No I, L, O, 0 or 1: nothing that reads as something else when said or copied out. */
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6
    const val WEB_URL = "https://yingwang.github.io/chinese_chess/"

    const val STATUS_WAITING = "waiting"
    const val STATUS_PLAYING = "playing"
    const val STATUS_FINISHED = "finished"

    private val secureRandom = SecureRandom()

    fun generateCode(random: Random = secureRandom): String =
        String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    /** What the player typed, upper-cased, without the spaces or dashes people add to read it out. */
    fun normalizeCode(input: String): String =
        input.uppercase(Locale.ROOT).filterNot { it.isWhitespace() || it == '-' }

    fun isValidCode(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it in CODE_ALPHABET }

    /** The web page with the room's code filled in, for the share sheet. */
    fun joinLink(code: String): String = "$WEB_URL?room=$code"

    fun colorKey(color: PieceColor): String = if (color == PieceColor.RED) "red" else "black"

    fun parseColor(value: Any?): PieceColor? = when (value) {
        "red" -> PieceColor.RED
        "black" -> PieceColor.BLACK
        else -> null
    }

    /** The side that makes move [index] (counting from 0). */
    fun moverOf(index: Int): PieceColor = if (index % 2 == 0) PieceColor.RED else PieceColor.BLACK

    data class WireMove(val from: Position, val to: Position, val serverTime: Long? = null)

    /** A move as the database holds it; the caller adds `t` as the server timestamp. */
    fun encodeMove(from: Position, to: Position): MutableMap<String, Any> = mutableMapOf(
        "fromRow" to from.row, "fromCol" to from.col,
        "toRow" to to.row, "toCol" to to.col
    )

    /** One move from the database (numbers arrive as Long), or null if it is not one. */
    fun decodeMove(value: Any?): WireMove? {
        val map = value as? Map<*, *> ?: return null
        fun int(key: String): Int? {
            val n = map[key] as? Number ?: return null
            val d = n.toDouble()
            return if (d == Math.floor(d)) d.toInt() else null
        }
        val from = Position(int("fromRow") ?: return null, int("fromCol") ?: return null)
        val to = Position(int("toRow") ?: return null, int("toCol") ?: return null)
        if (!from.isValid() || !to.isValid() || from == to) return null
        return WireMove(from, to, (map["t"] as? Number)?.toLong())
    }

    /**
     * The moves under `moves`, in order, as far as they run from 0 without a gap or a broken
     * entry. Firebase hands an index-keyed node over as a List when the keys look like an array
     * and as a Map otherwise; both are read the same.
     */
    fun decodeMoves(value: Any?): List<WireMove> {
        val byIndex: (Int) -> Any? = when (value) {
            is List<*> -> { i -> value.getOrNull(i) }
            is Map<*, *> -> { i -> value[i.toString()] }
            else -> return emptyList()
        }
        val moves = mutableListOf<WireMove>()
        while (true) {
            moves += decodeMove(byIndex(moves.size)) ?: break
        }
        return moves
    }

    enum class ResultType(val key: String) {
        CHECKMATE("checkmate"),
        STALEMATE("stalemate"),
        PERPETUAL_CHECK("perpetualCheck"),
        REPETITION("repetition"),
        RESIGN("resign");

        companion object {
            fun of(key: Any?): ResultType? = values().firstOrNull { it.key == key }
        }
    }

    /**
     * How the game ended; [winner] is null for a draw. A resignation carries the server's time
     * of it (`t`), where both sides stop the clocks; a result reached on the board needs none,
     * the clocks stop at the last move.
     */
    data class WireResult(val type: ResultType, val winner: PieceColor?, val serverTime: Long? = null)

    fun encodeResult(result: WireResult): Map<String, Any> {
        val map = mutableMapOf<String, Any>("type" to result.type.key)
        result.winner?.let { map["winner"] = colorKey(it) }
        return map
    }

    fun decodeResult(value: Any?): WireResult? {
        val map = value as? Map<*, *> ?: return null
        val type = ResultType.of(map["type"]) ?: return null
        return WireResult(type, parseColor(map["winner"]), (map["t"] as? Number)?.toLong())
    }

    /**
     * Red's and black's thinking time in milliseconds, from the server's clock: the game began
     * at [startedAt], move i was written at [moveTimes][i], and each interval between them is
     * charged to the side that was on the move. The side to move is charged up to [now]; pass
     * null for [now] once the game is over. An interval with an unknown end (a move without a
     * timestamp, or no start time) is left out rather than guessed.
     */
    fun sideTimes(startedAt: Long?, moveTimes: List<Long?>, now: Long?): Pair<Long, Long> {
        var red = 0L
        var black = 0L
        var since = startedAt
        fun charge(index: Int, until: Long?) {
            val start = since ?: return
            val end = until ?: return
            val spent = (end - start).coerceAtLeast(0L)
            if (moverOf(index) == PieceColor.RED) red += spent else black += spent
        }
        for ((i, t) in moveTimes.withIndex()) {
            charge(i, t)
            since = t
        }
        charge(moveTimes.size, now)
        return red to black
    }
}
