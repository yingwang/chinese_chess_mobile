package com.yingwang.chinesechess

import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL
) {
    enum class AIDifficulty(val depth: Int, val timeLimit: Long) {
        BEGINNER(2, 1000),
        INTERMEDIATE(4, 3000),
        ADVANCED(5, 5000),
        PROFESSIONAL(6, 8000),
        MASTER(7, 15000)
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
        maxDepth = aiDifficulty.depth,
        timeLimit = aiDifficulty.timeLimit
    )

    private var moveHistory = mutableListOf<Move>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move) -> Unit)? = null

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
        onBoardUpdated?.invoke(board)

        // If AI plays first
        if (gameMode != GameMode.PLAYER_VS_PLAYER && aiColor == PieceColor.RED) {
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

        // Make the move
        board.makeMoveInPlace(move)
        moveHistory.add(move)

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(move)

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
                    board.makeMoveInPlace(move)
                    moveHistory.add(move)

                    onBoardUpdated?.invoke(board)
                    onMoveCompleted?.invoke(move)

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
                val winner = board.currentPlayer.opposite()
                onGameOver?.invoke(GameResult.Checkmate(winner))
                return true
            }
            board.isStalemate() -> {
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
        return "Cache size: ${ai.getCacheSize()}, Difficulty: $aiDifficulty"
    }

    fun destroy() {
        coroutineScope.cancel()
    }
}
