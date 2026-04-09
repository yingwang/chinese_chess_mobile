package com.yingwang.chinesechess

import android.content.Context
import android.util.Log
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.ai.PikafishEngine
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL
) {
    private val difficulty: AIDifficulty = aiDifficulty
    private val soundManager = SoundManager(context)
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

        // Trigger AI move if needed
        if (shouldAIMove()) {
            makeAIMove()
        }
    }

    fun getCurrentBoard(): Board = board

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
            soundManager.playCaptureSound()
        } else {
            soundManager.playMoveSound()
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
            soundManager.playCheckSound()
        }

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(move)
        updateStats()

        // Check game over
        if (checkGameOver()) {
            return true
        }

        // AI's turn
        if (shouldAIMove()) {
            makeAIMove()
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
                val move = run {
                    val engine = ensurePikafish()
                    val depth = if (difficulty.pikafishDepth > 0) difficulty.pikafishDepth else 0
                    val timeMs = if (depth == 0) 10000L else 0L  // 棋圣: 10s unlimited
                    engine?.findBestMove(board, depth = depth, moveTimeMs = timeMs)
                        ?: fallbackAI.findBestMove(board, moveHistory)
                }

                if (move != null) {
                    // Avoid moves that would cause 3rd repetition (perpetual check = loss)
                    var finalMove = move
                    if (wouldCauseRepetition(move)) {
                        val legalMoves = board.getAllLegalMoves()
                        val safeMove = legalMoves.firstOrNull { !wouldCauseRepetition(it) }
                        if (safeMove != null) finalMove = safeMove
                    }

                    // Play appropriate sound
                    if (finalMove.capturedPiece != null) {
                        soundManager.playCaptureSound()
                    } else {
                        soundManager.playMoveSound()
                    }

                    // Update score and captured pieces if capturing
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

                    // Check for check condition and play sound
                    if (board.isInCheck(board.currentPlayer)) {
                        soundManager.playCheckSound()
                    }

                    onBoardUpdated?.invoke(board)
                    onMoveCompleted?.invoke(finalMove)
                    updateStats()

                    // Check game over
                    if (!checkGameOver()) {
                        // In AI vs AI mode, continue
                        if (gameMode == GameMode.AI_VS_AI) {
                            delay(500) // Brief pause for visualization
                            makeAIMove()
                        }
                    }
                }
            } finally {
                onAIThinking?.invoke(false)
            }
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
                soundManager.playGameOverSound()
                val winner = board.currentPlayer.opposite()
                onGameOver?.invoke(GameResult.Checkmate(winner))
                return true
            }
            board.isStalemate() -> {
                soundManager.playGameOverSound()
                onGameOver?.invoke(GameResult.Stalemate)
                return true
            }
        }

        // Repetition detection: same position 3 times
        val currentHash = positionHashes.last()
        val count = positionHashes.count { it == currentHash }
        if (count >= 3) {
            soundManager.playGameOverSound()
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
        return true
    }

    fun startEndgamePosition(position: EndgamePosition) {
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

    fun getReplayInfo(): String = if (replayMode) "第 $replayIndex / ${replayMoves.size} 步" else ""

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
        try {
            val json = JSONObject()
            json.put("gameMode", gameMode.name)
            json.put("aiColor", aiColor.name)
            json.put("difficulty", difficulty.name)

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

            gameStartTime = System.currentTimeMillis()
            onBoardUpdated?.invoke(board)
            updateStats()

            // Trigger AI move if it's AI's turn
            if (shouldAIMove()) {
                makeAIMove()
            }
            return true
        } catch (e: Exception) {
            return false
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
                val engine = ensurePikafish()
                val bestMove = engine?.findBestMove(board, moveTimeMs = 2000)
                    ?: fallbackAI.findBestMove(board, moveHistory)
                callback(bestMove)
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        soundManager.release()
        pikafishEngine?.close()
        pikafishEngine = null
    }

    fun setSoundEnabled(enabled: Boolean) {
        soundManager.setEnabled(enabled)
    }

    fun isSoundEnabled(): Boolean {
        return soundManager.isEnabled()
    }
}
