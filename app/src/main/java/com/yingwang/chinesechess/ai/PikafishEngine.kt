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
    private var isReady = false

    companion object {
        private const val TAG = "PikafishEngine"
        private const val BINARY_NAME = "pikafish_arm64"
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
    }

    /**
     * Extract binary and NNUE from assets to app files dir, start process.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val binaryFile = File(filesDir, BINARY_NAME)
        val nnueFile = File(filesDir, NNUE_NAME)

        // Extract binary if not present or outdated
        if (!binaryFile.exists() || needsUpdate(binaryFile)) {
            extractAsset(BINARY_NAME, binaryFile)
            binaryFile.setExecutable(true)
        }

        // Extract NNUE if not present
        if (!nnueFile.exists()) {
            extractAsset(NNUE_NAME, nnueFile)
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
        waitForResponse("uciok")

        sendCommand("setoption name Threads value 2")
        sendCommand("setoption name Hash value 32")
        sendCommand("isready")
        waitForResponse("readyok")

        isReady = true
        Log.i(TAG, "Pikafish engine started")
    }

    /**
     * Find best move for the given board position.
     * @param board Current board state
     * @param depth Search depth (higher = stronger)
     * @param moveTimeMs Time limit in milliseconds (0 = use depth only)
     * @return Best move, or null if no move found
     */
    suspend fun findBestMove(
        board: Board,
        depth: Int = 10,
        moveTimeMs: Long = 0
    ): Move? = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext null

        val fen = boardToFen(board)
        sendCommand("position fen $fen")

        val goCmd = if (moveTimeMs > 0) {
            "go movetime $moveTimeMs"
        } else {
            "go depth $depth"
        }
        sendCommand(goCmd)

        // Wait for bestmove response
        var bestMoveUci: String? = null
        while (true) {
            val line = reader?.readLine() ?: break
            Log.d(TAG, "< $line")
            if (line.startsWith("bestmove")) {
                bestMoveUci = line.split(" ").getOrNull(1)
                break
            }
        }

        if (bestMoveUci == null || bestMoveUci == "(none)") return@withContext null

        uciToMove(bestMoveUci, board)
    }

    /**
     * Stop any ongoing search.
     */
    fun stopSearch() {
        try {
            sendCommandSync("stop")
        } catch (_: Exception) {}
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

    private fun waitForResponse(expected: String) {
        while (true) {
            val line = reader?.readLine() ?: break
            Log.d(TAG, "< $line")
            if (line.startsWith(expected)) break
        }
    }

    // --- Asset extraction ---

    private fun extractAsset(assetName: String, targetFile: File) {
        Log.i(TAG, "Extracting $assetName to ${targetFile.absolutePath}")
        context.assets.open(assetName).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun needsUpdate(binaryFile: File): Boolean {
        // Re-extract if binary is older than APK install time
        try {
            val apkInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            return binaryFile.lastModified() < apkInfo.lastUpdateTime
        } catch (_: Exception) {
            return false
        }
    }
}
