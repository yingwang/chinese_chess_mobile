package com.yingwang.chinesechess

import android.content.Context
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL
) {
    private val difficulty: AIDifficulty = aiDifficulty
    private val soundManager = SoundManager(context)
    enum class AIDifficulty(val depth: Int, val timeLimit: Long, val quiescenceDepth: Int) {
        BEGINNER(1, 500, 0),  // No quiescence search for fastest response
        INTERMEDIATE(2, 1000, 1),
        ADVANCED(3, 2000, 2),
        PROFESSIONAL(4, 4000, 3),
        MASTER(5, 6000, 3)
    }

    enum class GameMode {
        PLAYER_VS_PLAYER,
        PLAYER_VS_AI,
        AI_VS_AI
    }

    private var board = Board.createInitialBoard()
    private var gameMode = GameMode.PLAYER_VS_AI
    private var aiColor = PieceColor.BLACK
    private val ai: ChessAI = ChessAI(
        maxDepth = difficulty.depth,
        timeLimit = difficulty.timeLimit,
        quiescenceDepth = difficulty.quiescenceDepth
    )

    private var moveHistory = mutableListOf<Move>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gameStartTime = 0L
    private var currentMoveStartTime = 0L
    private var redScore = 0
    private var blackScore = 0

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move) -> Unit)? = null
    var onStatsUpdated: ((GameStats) -> Unit)? = null

    data class GameStats(
        val redScore: Int,
        val blackScore: Int,
        val moveNumber: Int,
        val gameTime: Long,
        val lastMoveTime: Long
    )

    sealed class GameResult {
        data class Checkmate(val winner: PieceColor) : GameResult()
        object Stalemate : GameResult()
    }

    fun setGameMode(mode: GameMode, aiColor: PieceColor = PieceColor.BLACK) {
        this.gameMode = mode
        this.aiColor = aiColor
    }

    fun startNewGame() {
        board = Board.createInitialBoard()
        moveHistory.clear()
        ai.clearCache()
        gameStartTime = System.currentTimeMillis()
        currentMoveStartTime = gameStartTime
        redScore = 0
        blackScore = 0
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

        // Update score if capturing
        if (move.capturedPiece != null) {
            val captureValue = move.capturedPiece.type.baseValue
            if (move.piece.color == PieceColor.RED) {
                redScore += captureValue
            } else {
                blackScore += captureValue
            }
        }

        // Make the move
        val moveStartTime = currentMoveStartTime
        board.makeMoveInPlace(move)
        moveHistory.add(move)
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

    fun makeAIMove() {
        if (board.isCheckmate() || board.isStalemate()) return

        onAIThinking?.invoke(true)

        coroutineScope.launch {
            try {
                val move = ai.findBestMove(board)

                if (move != null) {
                    // Play appropriate sound
                    if (move.capturedPiece != null) {
                        soundManager.playCaptureSound()
                    } else {
                        soundManager.playMoveSound()
                    }

                    // Update score if capturing
                    if (move.capturedPiece != null) {
                        val captureValue = move.capturedPiece.type.baseValue
                        if (move.piece.color == PieceColor.RED) {
                            redScore += captureValue
                        } else {
                            blackScore += captureValue
                        }
                    }

                    board.makeMoveInPlace(move)
                    moveHistory.add(move)
                    currentMoveStartTime = System.currentTimeMillis()

                    // Check for check condition and play sound
                    if (board.isInCheck(board.currentPlayer)) {
                        soundManager.playCheckSound()
                    }

                    onBoardUpdated?.invoke(board)
                    onMoveCompleted?.invoke(move)
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
        return false
    }

    fun undoLastMove(): Boolean {
        if (moveHistory.isEmpty()) return false

        // In player vs AI mode, undo two moves (player and AI)
        val movesToUndo = if (gameMode == GameMode.PLAYER_VS_AI) 2 else 1

        repeat(movesToUndo.coerceAtMost(moveHistory.size)) {
            moveHistory.removeAt(moveHistory.size - 1)
        }

        // Rebuild board from history
        board = Board.createInitialBoard()
        for (move in moveHistory) {
            board.makeMoveInPlace(move)
        }

        onBoardUpdated?.invoke(board)
        return true
    }

    fun getAIStats(): String {
        return "Cache size: ${ai.getCacheSize()}, Difficulty: $difficulty"
    }

    fun getGameMode(): GameMode = gameMode

    fun getAIColor(): PieceColor = aiColor

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
            lastMoveTime = lastMoveTime
        )
        onStatsUpdated?.invoke(stats)
    }

    fun destroy() {
        coroutineScope.cancel()
        soundManager.release()
    }

    fun setSoundEnabled(enabled: Boolean) {
        soundManager.setEnabled(enabled)
    }

    fun isSoundEnabled(): Boolean {
        return soundManager.isEnabled()
    }
}
