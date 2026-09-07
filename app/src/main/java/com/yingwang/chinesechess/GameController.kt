package com.yingwang.chinesechess

import android.content.Context
import android.util.Log
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.ai.PikafishEngine
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL,
    private val audio: GameAudioManager
) {
    private val difficulty: AIDifficulty = aiDifficulty
    /** Bumped whenever the position is reset, so a search finished against an old game is dropped. */
    private var gameGeneration = 0
    /** One conversation with the engine process at a time. */
    private val engineMutex = Mutex()

    /**
     * Engine view of the position from red's side: centipawns, or moves to mate
     * (positive = red mates). Null when the engine is unavailable.
     */
    data class Evaluation(val cpRed: Int?, val mateRed: Int?)
    enum class AIDifficulty(val pikafishDepth: Int) {
        BEGINNER(3),
        INTERMEDIATE(6),
        ADVANCED(10),
        PROFESSIONAL(15),
        MASTER(20),
        GRANDMASTER(0)        // Pikafish unlimited depth
    }

    enum class GameMode {
        PLAYER_VS_PLAYER,
        PLAYER_VS_AI,
        AI_VS_AI
    }

    private var board = Board.createInitialBoard()
    private var initialBoard = Board.createInitialBoard()
    private var gameMode = GameMode.PLAYER_VS_AI
    private var aiColor = PieceColor.BLACK
    private var isEndgameMode = false
    private val fallbackAI: ChessAI = ChessAI(maxDepth = 3, timeLimit = 2000, quiescenceDepth = 2)
    private var pikafishEngine: PikafishEngine? = null

    private var moveHistory = mutableListOf<Move>()
    private var positionHashes = mutableListOf<Long>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameStartTime = 0L
    private var currentMoveStartTime = 0L
    private var redScore = 0
    private var blackScore = 0
    private val redCapturedPieces = mutableListOf<Piece>()
    private val blackCapturedPieces = mutableListOf<Piece>()

    // Replay state
    private var replayMode = false
    private var replayIndex = 0
    private var replayMoves = listOf<Move>()

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onEvaluationUpdated: ((Evaluation?) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move) -> Unit)? = null
    var onStatsUpdated: ((GameStats) -> Unit)? = null
    var onMoveAnimationRequested: ((Move, Board) -> Unit)? = null

    data class GameStats(
        val redScore: Int,
        val blackScore: Int,
        val moveNumber: Int,
        val gameTime: Long,
        val lastMoveTime: Long,
        val redCapturedPieces: List<Piece> = emptyList(),
        val blackCapturedPieces: List<Piece> = emptyList()
    )

    sealed class GameResult {
        data class Checkmate(val winner: PieceColor) : GameResult()
        object Stalemate : GameResult()
        data class PerpetualCheck(val winner: PieceColor) : GameResult()
        object RepetitionDraw : GameResult()
    }

    fun setGameMode(mode: GameMode, aiColor: PieceColor = PieceColor.BLACK) {
        this.gameMode = mode
        this.aiColor = aiColor
    }

    fun startNewGame() {
        gameGeneration++
        replayMode = false
        isEndgameMode = false
        board = Board.createInitialBoard()
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        gameStartTime = System.currentTimeMillis()
        currentMoveStartTime = gameStartTime
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)

        // Trigger AI move if needed
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }
    }

    fun getCurrentBoard(): Board = board

    /** Position the current game started from (standard, or an endgame study). */
    fun getInitialBoard(): Board = initialBoard

    fun getMoveHistory(): List<Move> = moveHistory.toList()

    fun makePlayerMove(move: Move): Boolean {
        if (replayMode) return false

        // Validate move
        val legalMoves = board.getAllLegalMoves()
        if (move !in legalMoves) {
            return false
        }

        // Play appropriate sound
        if (move.capturedPiece != null) {
            audio.playCaptureSound()
        } else {
            audio.playMoveSound()
        }

        // Update score and captured pieces if capturing
        if (move.capturedPiece != null) {
            val captureValue = move.capturedPiece.type.baseValue
            if (move.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(move.capturedPiece)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(move.capturedPiece)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(move, board.copy())

        // Make the move
        val moveStartTime = currentMoveStartTime
        board.makeMoveInPlace(move)
        moveHistory.add(move)
        positionHashes.add(board.getPositionHash())
        currentMoveStartTime = System.currentTimeMillis()

        // Check for check condition and play sound
        if (board.isInCheck(board.currentPlayer)) {
            audio.playCheckSound()
        }

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(move)
        updateStats()

        // Check game over
        if (checkGameOver()) {
            return true
        }

        // AI's turn; its search reports the evaluation, otherwise ask for one.
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }

        return true
    }

    private fun shouldAIMove(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> false
            GameMode.PLAYER_VS_AI -> board.currentPlayer == aiColor
            GameMode.AI_VS_AI -> true
        }
    }

    private suspend fun ensurePikafish(): PikafishEngine? {
        if (pikafishEngine != null) return pikafishEngine
        return try {
            val engine = PikafishEngine(context)
            engine.start()
            pikafishEngine = engine
            engine
        } catch (e: Exception) {
            Log.e("GameController", "Failed to start Pikafish", e)
            null
        }
    }

    fun makeAIMove() {
        if (board.isCheckmate() || board.isStalemate()) return

        onAIThinking?.invoke(true)

        coroutineScope.launch {
            try {
                val generation = gameGeneration
                val thinkStart = System.currentTimeMillis()
                val sideToMove = board.currentPlayer
                var (move, score) = searchBestMove(null)

                // Would this be the third time the position appears? Search again with the
                // repeating moves off the table instead of grabbing the first legal move.
                if (move != null && wouldCauseRepetition(move)) {
                    val safeMoves = board.getAllLegalMoves().filterNot { wouldCauseRepetition(it) }
                    val (alternative, altScore) = searchBestMove(safeMoves)
                    if (alternative != null) {
                        move = alternative
                        score = altScore
                    }
                }

                if (move != null) {
                    // A reply that lands the instant the player lifts a finger reads as a glitch;
                    // hold it back so every AI move takes at least MIN_THINK_MS.
                    val elapsed = System.currentTimeMillis() - thinkStart
                    if (elapsed < MIN_THINK_MS) delay(MIN_THINK_MS - elapsed)
                    if (generation == gameGeneration) {
                        // The root score of the search is the engine's view of the line it chose.
                        onEvaluationUpdated?.invoke(toRedPerspective(score, sideToMove))
                        applyAIMove(move)
                    }
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    /**
     * Engine search, optionally restricted to [candidates]; null means every legal move.
     * Returns the move and, when Pikafish produced it, its root score for the side to move.
     */
    private suspend fun searchBestMove(candidates: List<Move>?): Pair<Move?, PikafishEngine.Score?> {
        if (candidates != null && candidates.isEmpty()) return null to null
        val depth = if (difficulty.pikafishDepth > 0) difficulty.pikafishDepth else 0
        val timeMs = if (depth == 0) 10000L else 0L  // 棋圣: 10s unlimited
        val fromEngine = engineMutex.withLock {
            val engine = ensurePikafish() ?: return@withLock null
            val move = engine.findBestMove(board, depth = depth, moveTimeMs = timeMs, searchMoves = candidates)
            if (move != null) move to engine.lastScore else null
        }
        if (fromEngine != null) return fromEngine
        return fallbackAI.findBestMove(board, moveHistory, allowedMoves = candidates) to null
    }

    private fun toRedPerspective(score: PikafishEngine.Score?, sideToMove: PieceColor): Evaluation? {
        if (score == null) return null
        val sign = if (sideToMove == PieceColor.RED) 1 else -1
        return Evaluation(cpRed = score.cp?.let { it * sign }, mateRed = score.mate?.let { it * sign })
    }

    /**
     * Shallow engine evaluation of [target] (the live board by default), published through
     * [onEvaluationUpdated] unless the game moved on while it ran.
     */
    private fun refreshEvaluation(target: Board = board) {
        val snapshot = target.copy()
        if (snapshot.isCheckmate() || snapshot.isStalemate()) return
        val generation = gameGeneration
        val moveCount = moveHistory.size
        val replayAt = replayIndex
        coroutineScope.launch {
            val score = engineMutex.withLock {
                val engine = ensurePikafish() ?: return@withLock null
                engine.evaluate(snapshot, depth = 10)
            }
            if (generation == gameGeneration && moveCount == moveHistory.size && replayAt == replayIndex) {
                onEvaluationUpdated?.invoke(toRedPerspective(score, snapshot.currentPlayer))
            }
        }
    }

    private suspend fun applyAIMove(finalMove: Move) {
        if (finalMove.capturedPiece != null) audio.playCaptureSound() else audio.playMoveSound()

        val captured = finalMove.capturedPiece
        if (captured != null) {
            val captureValue = captured.type.baseValue
            if (finalMove.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(captured)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(captured)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(finalMove, board.copy())

        board.makeMoveInPlace(finalMove)
        moveHistory.add(finalMove)
        positionHashes.add(board.getPositionHash())
        currentMoveStartTime = System.currentTimeMillis()

        if (board.isInCheck(board.currentPlayer)) audio.playCheckSound()

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(finalMove)
        updateStats()

        if (!checkGameOver() && gameMode == GameMode.AI_VS_AI) {
            delay(500) // Brief pause for visualization
            makeAIMove()
        }
    }

    private fun wouldCauseRepetition(move: Move): Boolean {
        val testBoard = board.copy()
        testBoard.makeMoveInPlace(move)
        val hash = testBoard.getPositionHash()
        return positionHashes.count { it == hash } >= 2 // would be 3rd occurrence
    }

    private fun checkGameOver(): Boolean {
        when {
            board.isCheckmate() -> {
                audio.playGameOverSound()
                val winner = board.currentPlayer.opposite()
                onGameOver?.invoke(GameResult.Checkmate(winner))
                return true
            }
            board.isStalemate() -> {
                audio.playGameOverSound()
                onGameOver?.invoke(GameResult.Stalemate)
                return true
            }
        }

        // Repetition detection: same position 3 times
        val currentHash = positionHashes.last()
        val count = positionHashes.count { it == currentHash }
        if (count >= 3) {
            audio.playGameOverSound()
            if (board.isInCheck(board.currentPlayer)) {
                // Current player is in check → opponent perpetually checking → opponent loses
                val winner = board.currentPlayer
                onGameOver?.invoke(GameResult.PerpetualCheck(winner))
            } else {
                onGameOver?.invoke(GameResult.RepetitionDraw)
            }
            return true
        }

        return false
    }

    fun undoLastMove(): Boolean {
        gameGeneration++
        if (moveHistory.isEmpty()) return false

        // In player vs AI mode, undo two moves (player and AI)
        val movesToUndo = if (gameMode == GameMode.PLAYER_VS_AI) 2 else 1

        repeat(movesToUndo.coerceAtMost(moveHistory.size)) {
            moveHistory.removeAt(moveHistory.size - 1)
        }

        // Rebuild board and captured pieces from history
        board = initialBoard.copy()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        for (move in moveHistory) {
            if (move.capturedPiece != null) {
                val captureValue = move.capturedPiece.type.baseValue
                if (move.piece.color == PieceColor.RED) {
                    redScore += captureValue
                    redCapturedPieces.add(move.capturedPiece)
                } else {
                    blackScore += captureValue
                    blackCapturedPieces.add(move.capturedPiece)
                }
            }
            board.makeMoveInPlace(move)
            positionHashes.add(board.getPositionHash())
        }

        onBoardUpdated?.invoke(board)
        updateStats()
        refreshEvaluation()
        return true
    }

    fun startEndgamePosition(position: EndgamePosition) {
        gameGeneration++
        replayMode = false
        isEndgameMode = true
        board = Board.createFromPieces(position.pieces, position.firstPlayer)
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        gameStartTime = System.currentTimeMillis()
        currentMoveStartTime = gameStartTime
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        gameMode = GameMode.PLAYER_VS_AI
        aiColor = PieceColor.BLACK
        onBoardUpdated?.invoke(board)
        updateStats()
        if (!shouldAIMove()) refreshEvaluation()
    }

    fun isEndgameMode(): Boolean = isEndgameMode

    // --- Replay Mode ---

    fun enterReplayMode(): Boolean {
        if (moveHistory.isEmpty()) return false
        replayMode = true
        replayMoves = moveHistory.toList()
        replayIndex = replayMoves.size
        return true
    }

    fun exitReplayMode() {
        gameGeneration++
        replayMode = false
        replayMoves = emptyList()
        replayIndex = 0
        onBoardUpdated?.invoke(board)
    }

    fun isInReplayMode() = replayMode

    fun replayStepBack(): Boolean {
        if (!replayMode || replayIndex <= 0) return false
        replayIndex--
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayStepForward(): Boolean {
        if (!replayMode || replayIndex >= replayMoves.size) return false
        replayIndex++
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayToStart() {
        if (!replayMode) return
        replayIndex = 0
        rebuildBoardToIndex(0)
    }

    fun replayToEnd() {
        if (!replayMode) return
        replayIndex = replayMoves.size
        rebuildBoardToIndex(replayIndex)
    }

    fun getReplayInfo(): String =
        if (replayMode) context.getString(R.string.replay_progress, replayIndex, replayMoves.size) else ""

    private fun rebuildBoardToIndex(index: Int) {
        val tempBoard = initialBoard.copy()
        for (i in 0 until index) {
            tempBoard.makeMoveInPlace(replayMoves[i])
        }
        val lastReplayMove = if (index > 0) replayMoves[index - 1] else null
        onBoardUpdated?.invoke(tempBoard)
        if (lastReplayMove != null) {
            onMoveCompleted?.invoke(lastReplayMove)
        }
        refreshEvaluation(tempBoard)
    }

    fun getAIStats(): String {
        return "Difficulty: $difficulty, Engine: ${if (pikafishEngine != null) "Pikafish" else "fallback"}"
    }

    fun getGameMode(): GameMode = gameMode

    fun getAIColor(): PieceColor = aiColor

    fun getDifficulty(): AIDifficulty = difficulty

    fun getGameStartTime(): Long = gameStartTime

    fun isPlayerTurn(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> true
            GameMode.PLAYER_VS_AI -> board.currentPlayer != aiColor
            GameMode.AI_VS_AI -> false
        }
    }

    private fun updateStats() {
        val gameTime = System.currentTimeMillis() - gameStartTime
        val lastMoveTime = System.currentTimeMillis() - currentMoveStartTime
        val stats = GameStats(
            redScore = redScore,
            blackScore = blackScore,
            moveNumber = moveHistory.size,
            gameTime = gameTime,
            lastMoveTime = lastMoveTime,
            redCapturedPieces = redCapturedPieces.toList(),
            blackCapturedPieces = blackCapturedPieces.toList()
        )
        onStatsUpdated?.invoke(stats)
    }

    fun saveGame(context: Context): Boolean {
        if (isEndgameMode) {
            // Endgame studies start from a custom position the save format does
            // not carry; replaying their moves onto the standard opening produced
            // a scrambled board on resume. They are short, so just do not persist.
            deleteSavedGame(context)
            return false
        }
        try {
            val json = JSONObject()
            json.put("gameMode", gameMode.name)
            json.put("aiColor", aiColor.name)
            json.put("difficulty", difficulty.name)
            json.put("elapsedMs", System.currentTimeMillis() - gameStartTime)

            val movesArray = JSONArray()
            for (move in moveHistory) {
                val moveJson = JSONObject()
                moveJson.put("fromRow", move.from.row)
                moveJson.put("fromCol", move.from.col)
                moveJson.put("toRow", move.to.row)
                moveJson.put("toCol", move.to.col)
                moveJson.put("pieceType", move.piece.type.name)
                moveJson.put("pieceColor", move.piece.color.name)
                if (move.capturedPiece != null) {
                    moveJson.put("capturedType", move.capturedPiece.type.name)
                    moveJson.put("capturedColor", move.capturedPiece.color.name)
                }
                movesArray.put(moveJson)
            }
            json.put("moves", movesArray)

            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_game", json.toString()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun loadGame(context: Context): Boolean {
        gameGeneration++
        try {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return false
            val json = JSONObject(jsonStr)

            // Restore game mode
            gameMode = GameMode.valueOf(json.getString("gameMode"))
            aiColor = PieceColor.valueOf(json.getString("aiColor"))

            // Replay all moves
            board = Board.createInitialBoard()
            initialBoard = board.copy()
            isEndgameMode = false
            replayMode = false
            moveHistory.clear()
            positionHashes.clear()
            positionHashes.add(board.getPositionHash())
            redScore = 0
            blackScore = 0
            redCapturedPieces.clear()
            blackCapturedPieces.clear()

            val movesArray = json.getJSONArray("moves")
            for (i in 0 until movesArray.length()) {
                val moveJson = movesArray.getJSONObject(i)
                val from = Position(moveJson.getInt("fromRow"), moveJson.getInt("fromCol"))
                val to = Position(moveJson.getInt("toRow"), moveJson.getInt("toCol"))
                val piece = board.getPiece(from) ?: continue
                val capturedPiece = board.getPiece(to)
                val move = Move(from, to, piece, capturedPiece)

                if (capturedPiece != null) {
                    val captureValue = capturedPiece.type.baseValue
                    if (piece.color == PieceColor.RED) {
                        redScore += captureValue
                        redCapturedPieces.add(capturedPiece)
                    } else {
                        blackScore += captureValue
                        blackCapturedPieces.add(capturedPiece)
                    }
                }

                board.makeMoveInPlace(move)
                moveHistory.add(move)
                positionHashes.add(board.getPositionHash())
            }

            // Resume the clock where it stopped rather than from zero.
            gameStartTime = System.currentTimeMillis() - json.optLong("elapsedMs", 0L)
            currentMoveStartTime = System.currentTimeMillis()
            onBoardUpdated?.invoke(board)
            updateStats()
            if (!shouldAIMove()) refreshEvaluation()

            // Trigger AI move if it's AI's turn
            if (shouldAIMove()) {
                makeAIMove()
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    companion object {
        /** Floor on how long an AI move appears to take, so replies never look instant. */
        private const val MIN_THINK_MS = 900L

        /** Difficulty stored with the saved game, so resuming rebuilds the same opponent. */
        fun savedDifficulty(context: Context): AIDifficulty? {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return null
            return try {
                AIDifficulty.valueOf(JSONObject(jsonStr).getString("difficulty"))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun hasSavedGame(context: Context): Boolean {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        return prefs.contains("saved_game")
    }

    fun deleteSavedGame(context: Context) {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        prefs.edit().remove("saved_game").apply()
    }

    fun getHint(callback: (Move?) -> Unit) {
        if (board.isCheckmate() || board.isStalemate()) {
            callback(null)
            return
        }

        onAIThinking?.invoke(true)
        coroutineScope.launch {
            try {
                val sideToMove = board.currentPlayer
                val fromEngine = engineMutex.withLock {
                    val engine = ensurePikafish() ?: return@withLock null
                    val move = engine.findBestMove(board, moveTimeMs = 2000)
                    if (move != null) move to engine.lastScore else null
                }
                if (fromEngine != null) {
                    onEvaluationUpdated?.invoke(toRedPerspective(fromEngine.second, sideToMove))
                    callback(fromEngine.first)
                } else {
                    callback(fallbackAI.findBestMove(board, moveHistory))
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        pikafishEngine?.close()
        pikafishEngine = null
    }
}
