package com.yingwang.chinesechess.ai

import android.content.Context
import android.util.Log
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

/**
 * Pikafish (Stockfish for xiangqi) UCI engine wrapper.
 * Runs pikafish as a subprocess and communicates via UCI protocol.
 */
class PikafishEngine(private val context: Context) : Closeable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Volatile private var isReady = false

    /**
     * False once the process is closed or found gone (it ended, or the pipe to it broke); every
     * search then returns nothing at once, and the controller starts another or plays without.
     */
    val isAlive: Boolean get() = isReady

    /**
     * Engine score for the side to move: centipawns, or moves to mate (positive = the side
     * to move mates). Taken from the last `info` line of the most recent search.
     */
    data class Score(val cp: Int?, val mate: Int?)

    /** Score reported by the most recent search, for the side that was to move. */
    var lastScore: Score? = null
        private set

    companion object {
        private const val TAG = "PikafishEngine"
        private const val BINARY_NAME = "libpikafish.so"
        private const val NNUE_NAME = "pikafish.nnue"

        private val PIECE_TO_FEN = mapOf(
            PieceType.CHARIOT to 'R',
            PieceType.HORSE to 'N',
            PieceType.ELEPHANT to 'B',
            PieceType.ADVISOR to 'A',
            PieceType.GENERAL to 'K',
            PieceType.CANNON to 'C',
            PieceType.SOLDIER to 'P'
        )

        /**
         * Whether the weights must be unpacked: missing ([onDiskLength] null), or not the size of
         * the copy in the app ([appLength], -1 when it cannot be told), which is an unpacking cut
         * short or a release with a new network. A complete copy is left as it is.
         */
        fun needsUnpacking(onDiskLength: Long?, appLength: Long): Boolean =
            onDiskLength == null || (appLength >= 0 && onDiskLength != appLength)
    }

    /**
     * Locate the engine binary, extract the NNUE weights, start the process.
     *
     * The binary ships in jniLibs rather than in assets because Android 10 and later refuse
     * to execute anything inside the app's own writable data directory; SELinux denies
     * execute_no_trans on app_data_file, so starting it from filesDir failed with
     * "error=13, Permission denied" and every difficulty silently fell back to the shallow
     * search. nativeLibraryDir is read-only and may be executed. The weights are plain data,
     * so they still come from assets.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val binaryFile = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        val nnueFile = File(filesDir, NNUE_NAME)

        check(binaryFile.canExecute()) { "Engine binary is missing or not executable: $binaryFile" }

        // Unpacked on first use, and again only when the copy on disk is not the app's (see
        // needsUnpacking): checking only that the file was there kept a truncated one for good,
        // and the engine quits on its first search with a network it cannot read.
        val appLength = assetLength(NNUE_NAME)
        if (needsUnpacking(nnueFile.takeIf { it.exists() }?.length(), appLength)) {
            extractAsset(NNUE_NAME, nnueFile, appLength)
        }

        // Start process
        val pb = ProcessBuilder(binaryFile.absolutePath)
        pb.directory(filesDir) // NNUE file is found relative to working dir
        pb.redirectErrorStream(true)
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
        reader = BufferedReader(InputStreamReader(process!!.inputStream))

        // Initialize UCI
        sendCommand("uci")
        check(waitForResponse("uciok")) { "Engine ended before uciok" }

        sendCommand("setoption name Threads value 2")
        sendCommand("setoption name Hash value 32")
        sendCommand("isready")
        check(waitForResponse("readyok")) { "Engine ended before readyok" }

        isReady = true
        Log.i(TAG, "Pikafish engine started")
    }

    /**
     * Find best move for the given board position.
     * @param board Current board state
     * @param depth Search depth (higher = stronger)
     * @param moveTimeMs Time limit in milliseconds (0 = use depth only)
     * @param searchMoves If given, the engine only considers these root moves (UCI
     *   `searchmoves`); used to steer away from a threefold repetition.
     * @return Best move, or null if no move found
     */
    suspend fun findBestMove(
        board: Board,
        depth: Int = 10,
        moveTimeMs: Long = 0,
        searchMoves: List<Move>? = null
    ): Move? = search<Move?>(null) {
        if (searchMoves != null && searchMoves.isEmpty()) return@search null

        val fen = boardToFen(board)
        sendCommand("position fen $fen")

        val goCmd = buildString {
            append(if (moveTimeMs > 0) "go movetime $moveTimeMs" else "go depth $depth")
            if (searchMoves != null) {
                append(" searchmoves ")
                append(searchMoves.joinToString(" ") { posToUci(it.from) + posToUci(it.to) })
            }
        }
        sendGo(goCmd)

        val bestMoveUci = readUntilBestMove()
        if (bestMoveUci == null || bestMoveUci == "(none)") null else uciToMove(bestMoveUci, board)
    }

    /**
     * The best [multiPv] root moves at [depth], best first, each with its score for the side
     * to move. Pikafish has no Skill Level or UCI_Elo, so the weak levels pick among these
     * themselves (see [WeakPlay]). MultiPV is put back to 1 afterwards; every other search
     * assumes it.
     */
    suspend fun findCandidates(
        board: Board,
        depth: Int,
        multiPv: Int,
        searchMoves: List<Move>? = null
    ): List<Pair<Move, Score>> = search<List<Pair<Move, Score>>>(emptyList()) {
        if (searchMoves != null && searchMoves.isEmpty()) return@search emptyList()

        sendCommand("setoption name MultiPV value $multiPv")
        sendCommand("position fen ${boardToFen(board)}")
        sendGo(buildString {
            append("go depth $depth")
            if (searchMoves != null) {
                append(" searchmoves ")
                append(searchMoves.joinToString(" ") { posToUci(it.from) + posToUci(it.to) })
            }
        })

        // The last line for each multipv index carries the deepest result for that move.
        val lines = sortedMapOf<Int, Pair<String, Score>>()
        while (true) {
            val line = reader?.readLine()
            if (line == null) {
                // The process ended before its bestmove (see readUntilBestMove).
                isReady = false
                return@search emptyList()
            }
            if (line.startsWith("info ")) {
                parseScore(line)?.let { lastScore = it }
                parseMultiPv(line)?.let { (index, uci, score) -> lines[index] = uci to score }
            } else if (line.startsWith("bestmove")) {
                break
            }
        }
        sendCommand("setoption name MultiPV value 1")

        lines.values.mapNotNull { (uci, score) -> uciToMove(uci, board)?.let { it to score } }
    }

    /** (multipv index, first move of its line, score) from an `info` line that has all three. */
    private fun parseMultiPv(line: String): Triple<Int, String, Score>? {
        val parts = line.split(" ")
        val m = parts.indexOf("multipv")
        if (m < 0) return null
        val index = parts.getOrNull(m + 1)?.toIntOrNull() ?: return null
        val pv = parts.indexOf("pv")
        val uci = parts.getOrNull(pv + 1)?.takeIf { pv >= 0 } ?: return null
        val i = parts.indexOf("score")
        if (i < 0) return null
        val value = parts.getOrNull(i + 2)?.toIntOrNull() ?: return null
        val score = when (parts.getOrNull(i + 1)) {
            "cp" -> Score(cp = value, mate = null)
            "mate" -> Score(cp = null, mate = value)
            else -> return null
        }
        return Triple(index, uci, score)
    }

    /**
     * Quick evaluation of a position, for the side to move. Shallow by default so it can run
     * after every move without a visible pause.
     */
    suspend fun evaluate(board: Board, depth: Int = 10): Score? = search<Score?>(null) {
        sendCommand("position fen ${boardToFen(board)}")
        sendGo("go depth $depth")
        readUntilBestMove()
        if (isReady) lastScore else null
    }

    /**
     * The score for the side to move and the move the search settled on, searching to [depth]
     * or, when [moveTimeMs] is set, for that long instead. Look at [isAlive] afterwards: a search
     * the process did not live through returns whatever it had.
     */
    suspend fun analyse(board: Board, depth: Int = 0, moveTimeMs: Long = 0): Pair<Score?, Move?> = search<Pair<Score?, Move?>>(null to null) {
        lastScore = null
        sendCommand("position fen ${boardToFen(board)}")
        sendGo(if (moveTimeMs > 0) "go movetime $moveTimeMs" else "go depth $depth")
        val uci = readUntilBestMove()
        lastScore to uci?.takeIf { it != "(none)" }?.let { uciToMove(it, board) }
    }

    // A `stop` the engine receives while it is idle is ignored, and one sent the moment a search
    // is asked for can reach it before that search's `go`: the search then ran its full length
    // for a caller that had moved on. So a stop is held from the moment a search is asked for
    // ([search], on the caller's thread) until its `go` is out, and sent straight after it.
    private val stopLock = Any()
    /** A search has been asked for and its `bestmove` not read yet. */
    private var armed = false
    private var goSent = false
    private var stopAsked = false

    private fun arm() {
        synchronized(stopLock) {
            armed = true
            goSent = false
            stopAsked = false
        }
    }

    private fun disarm() {
        synchronized(stopLock) {
            armed = false
            goSent = false
            stopAsked = false
        }
    }

    /** Sends the search's [command], and a `stop` straight after if one came while it was set up. */
    private fun sendGo(command: String) {
        synchronized(stopLock) {
            sendCommand(command)
            goSent = true
            if (stopAsked) sendCommand("stop")
        }
    }

    /**
     * One search, run on the I/O thread: [none] if the engine is gone, or turns out to be gone on
     * the way. A broken pipe to an engine that has ended is an IOException; it used to end the app.
     */
    private suspend fun <T> search(none: T, body: () -> T): T {
        arm()
        return withContext(Dispatchers.IO) {
            try {
                if (isReady) body() else none
            } catch (e: IOException) {
                Log.w(TAG, "engine gone", e)
                isReady = false
                none
            } finally {
                disarm()
            }
        }
    }

    /** Reads engine output up to `bestmove`, recording the score of each `info` line on the way. */
    private fun readUntilBestMove(): String? {
        var bestMoveUci: String? = null
        while (true) {
            val line = reader?.readLine()
            if (line == null) {
                // The process ended (it quits on a network it cannot use, for one): nothing more
                // will come, and writing to it would fail.
                isReady = false
                break
            }
            Log.d(TAG, "< $line")
            if (line.startsWith("info ")) {
                parseScore(line)?.let { lastScore = it }
            } else if (line.startsWith("bestmove")) {
                bestMoveUci = line.split(" ").getOrNull(1)
                break
            }
        }
        return bestMoveUci
    }

    private fun parseScore(line: String): Score? {
        val parts = line.split(" ")
        // Only the principal variation; ignore multipv 2+ and lines without a score.
        val pvIndex = parts.indexOf("multipv")
        if (pvIndex >= 0 && parts.getOrNull(pvIndex + 1) != "1") return null
        val i = parts.indexOf("score")
        if (i < 0) return null
        val kind = parts.getOrNull(i + 1) ?: return null
        val value = parts.getOrNull(i + 2)?.toIntOrNull() ?: return null
        return when (kind) {
            "cp" -> Score(cp = value, mate = null)
            "mate" -> Score(cp = null, mate = value)
            else -> null
        }
    }

    /**
     * Stop any ongoing search: at once if its `go` is out, otherwise as soon as it is (see
     * [sendGo]). Nothing when no search has been asked for.
     */
    fun stopSearch() {
        synchronized(stopLock) {
            if (goSent) {
                try {
                    sendCommandSync("stop")
                } catch (_: Exception) {}
            } else if (armed) {
                stopAsked = true
            }
        }
    }

    override fun close() {
        try {
            sendCommandSync("quit")
        } catch (_: Exception) {}
        process?.destroy()
        process = null
        writer = null
        reader = null
        isReady = false
    }

    // --- FEN conversion ---

    fun boardToFen(board: Board): String {
        val sb = StringBuilder()

        for (row in 0 until Board.ROWS) {
            var empty = 0
            for (col in 0 until Board.COLS) {
                val piece = board.getPiece(Position(row, col))
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    val ch = PIECE_TO_FEN[piece.type] ?: 'P'
                    sb.append(if (piece.color == PieceColor.RED) ch.uppercaseChar() else ch.lowercaseChar())
                }
            }
            if (empty > 0) sb.append(empty)
            if (row < Board.ROWS - 1) sb.append('/')
        }

        sb.append(if (board.currentPlayer == PieceColor.RED) " w" else " b")
        sb.append(" - - 0 1")

        return sb.toString()
    }

    // --- UCI coordinate conversion ---

    /**
     * Convert Board (row, col) to UCI square string.
     * Board row 0 = top (black back rank) = UCI row 9
     * Board row 9 = bottom (red back rank) = UCI row 0
     */
    private fun posToUci(pos: Position): String {
        val col = ('a' + pos.col)
        val row = 9 - pos.row
        return "$col$row"
    }

    /**
     * Convert UCI square string to Board Position.
     */
    private fun uciToPos(uci: String): Position {
        val col = uci[0] - 'a'
        val row = 9 - (uci[1] - '0')
        return Position(row, col)
    }

    /**
     * Convert UCI move string (e.g. "e9e8") to a Move object.
     */
    private fun uciToMove(uci: String, board: Board): Move? {
        if (uci.length < 4) return null
        val from = uciToPos(uci.substring(0, 2))
        val to = uciToPos(uci.substring(2, 4))
        val piece = board.getPiece(from) ?: return null
        val captured = board.getPiece(to)
        return Move(from, to, piece, captured)
    }

    // --- Process I/O ---

    private fun sendCommand(cmd: String) {
        Log.d(TAG, "> $cmd")
        writer?.write("$cmd\n")
        writer?.flush()
    }

    private fun sendCommandSync(cmd: String) {
        writer?.write("$cmd\n")
        writer?.flush()
    }

    /** Reads up to the line starting with [expected]; false if the process ended first. */
    private fun waitForResponse(expected: String): Boolean {
        while (true) {
            val line = reader?.readLine() ?: return false
            Log.d(TAG, "< $line")
            if (line.startsWith(expected)) return true
        }
    }

    // --- Asset extraction ---

    /** Size of [assetName] in the app; -1 if it cannot be told (were it stored compressed, say). */
    private fun assetLength(assetName: String): Long = try {
        context.assets.openFd(assetName).use { it.length }
    } catch (_: Exception) {
        -1L
    }

    /**
     * Unpacks [assetName] to [targetFile] through a file of its own, put in place only once it is
     * whole ([length] long, when that is known), so an unpacking cut short (the app ended, the
     * storage full) never stands as the real thing. On failure whatever was there is left as it
     * was and the engine is not started; the built-in search plays instead.
     */
    private fun extractAsset(assetName: String, targetFile: File, length: Long) {
        Log.i(TAG, "Extracting $assetName to ${targetFile.absolutePath}")
        val part = File(targetFile.parentFile, targetFile.name + ".part")
        // Not begun without room for it: filling the storage to the last byte troubles every app.
        if (length > 0) check((targetFile.parentFile?.usableSpace ?: 0L) > length) { "No room to unpack $assetName" }
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output)
                }
            }
            if (length >= 0) check(part.length() == length) { "$assetName unpacked short" }
            if (!part.renameTo(targetFile)) throw IOException("Could not move $part into place")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }
}
