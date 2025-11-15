package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*

/**
 * Professional-level Chinese Chess AI using:
 * - Alpha-beta pruning
 * - Iterative deepening
 * - Transposition tables
 * - Move ordering
 * - Quiescence search
 */
class ChessAI(
    private val maxDepth: Int = 6,
    private val timeLimit: Long = 5000, // milliseconds
    private val quiescenceDepth: Int = 3 // depth for quiescence search
) {
    private val transpositionTable = TranspositionTable()
    private var nodesSearched = 0
    private var startTime = 0L
    private var shouldStop = false

    companion object {
        private const val INFINITY = 1_000_000
        private const val CHECKMATE_SCORE = 100_000
    }

    /**
     * Find the best move for the current player
     */
    suspend fun findBestMove(board: Board): Move? = withContext(Dispatchers.Default) {
        nodesSearched = 0
        startTime = System.currentTimeMillis()
        shouldStop = false

        var bestMove: Move? = null
        var bestScore = Int.MIN_VALUE

        // Iterative deepening
        for (depth in 1..maxDepth) {
            if (shouldStop) break

            val result = searchWithTimeout(board, depth)
            if (result != null) {
                bestMove = result.first
                bestScore = result.second

                // Log search statistics
                val elapsed = System.currentTimeMillis() - startTime
                println("Depth $depth: score=$bestScore, nodes=$nodesSearched, time=${elapsed}ms, move=$bestMove")

                // If we found a checkmate, no need to search deeper
                if (Math.abs(bestScore) > CHECKMATE_SCORE - 100) {
                    break
                }
            }

            // Check time limit
            if (System.currentTimeMillis() - startTime > timeLimit) {
                shouldStop = true
            }
        }

        println("AI selected move: $bestMove with score $bestScore (searched $nodesSearched nodes)")
        bestMove
    }

    private fun searchWithTimeout(board: Board, depth: Int): Pair<Move, Int>? {
        var bestMove: Move? = null
        var bestScore = if (board.currentPlayer == PieceColor.RED) Int.MIN_VALUE else Int.MAX_VALUE

        val moves = orderMoves(board, board.getAllLegalMoves())
        if (moves.isEmpty()) return null

        for (move in moves) {
            if (shouldStop) break

            val newBoard = board.makeMove(move)
            newBoard.currentPlayer = board.currentPlayer.opposite()

            val score = if (board.currentPlayer == PieceColor.RED) {
                alphaBeta(newBoard, depth - 1, Int.MIN_VALUE, Int.MAX_VALUE, false)
            } else {
                alphaBeta(newBoard, depth - 1, Int.MIN_VALUE, Int.MAX_VALUE, true)
            }

            if (board.currentPlayer == PieceColor.RED) {
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }
            }
        }

        return if (bestMove != null) Pair(bestMove, bestScore) else null
    }

    /**
     * Alpha-beta search with transposition table
     */
    private fun alphaBeta(
        board: Board,
        depth: Int,
        alpha: Int,
        beta: Int,
        maximizing: Boolean
    ): Int {
        nodesSearched++

        if (shouldStop) return 0

        // Check transposition table
        val hash = board.getPositionHash()
        val ttEntry = transpositionTable.probe(hash)
        if (ttEntry != null && ttEntry.depth >= depth) {
            when (ttEntry.type) {
                TranspositionTable.EntryType.EXACT -> return ttEntry.score
                TranspositionTable.EntryType.LOWER_BOUND -> {
                    if (ttEntry.score >= beta) return ttEntry.score
                }
                TranspositionTable.EntryType.UPPER_BOUND -> {
                    if (ttEntry.score <= alpha) return ttEntry.score
                }
            }
        }

        // Terminal conditions
        if (depth == 0) {
            return quiescenceSearch(board, alpha, beta, maximizing, quiescenceDepth)
        }

        if (board.isCheckmate()) {
            return if (maximizing) -CHECKMATE_SCORE + (maxDepth - depth) else CHECKMATE_SCORE - (maxDepth - depth)
        }

        if (board.isStalemate()) {
            return 0
        }

        val moves = orderMoves(board, board.getAllLegalMoves(), ttEntry?.bestMove)
        if (moves.isEmpty()) {
            return Evaluator.evaluate(board)
        }

        var currentAlpha = alpha
        var currentBeta = beta
        var bestScore: Int
        var bestMove: Move? = null

        if (maximizing) {
            bestScore = Int.MIN_VALUE
            for (move in moves) {
                if (shouldStop) break

                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                val score = alphaBeta(newBoard, depth - 1, currentAlpha, currentBeta, false)

                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }

                currentAlpha = maxOf(currentAlpha, score)
                if (currentBeta <= currentAlpha) {
                    // Beta cutoff
                    transpositionTable.store(
                        hash, depth, bestScore,
                        TranspositionTable.EntryType.LOWER_BOUND, bestMove
                    )
                    return bestScore
                }
            }
        } else {
            bestScore = Int.MAX_VALUE
            for (move in moves) {
                if (shouldStop) break

                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                val score = alphaBeta(newBoard, depth - 1, currentAlpha, currentBeta, true)

                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }

                currentBeta = minOf(currentBeta, score)
                if (currentBeta <= currentAlpha) {
                    // Alpha cutoff
                    transpositionTable.store(
                        hash, depth, bestScore,
                        TranspositionTable.EntryType.UPPER_BOUND, bestMove
                    )
                    return bestScore
                }
            }
        }

        // Store exact score in transposition table
        transpositionTable.store(
            hash, depth, bestScore,
            TranspositionTable.EntryType.EXACT, bestMove
        )

        return bestScore
    }

    /**
     * Quiescence search to avoid horizon effect
     * Only searches capture moves to find quiet positions
     */
    private fun quiescenceSearch(
        board: Board,
        alpha: Int,
        beta: Int,
        maximizing: Boolean,
        depth: Int
    ): Int {
        nodesSearched++

        if (depth == 0) {
            return Evaluator.evaluate(board)
        }

        val standPat = Evaluator.evaluate(board)

        if (maximizing) {
            if (standPat >= beta) return beta
            var currentAlpha = maxOf(alpha, standPat)

            val captureMoves = board.getAllLegalMoves().filter { it.isCapture() }
            for (move in orderMoves(board, captureMoves)) {
                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                val score = quiescenceSearch(newBoard, currentAlpha, beta, false, depth - 1)

                currentAlpha = maxOf(currentAlpha, score)
                if (currentAlpha >= beta) {
                    return beta
                }
            }
            return currentAlpha
        } else {
            if (standPat <= alpha) return alpha
            var currentBeta = minOf(beta, standPat)

            val captureMoves = board.getAllLegalMoves().filter { it.isCapture() }
            for (move in orderMoves(board, captureMoves)) {
                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                val score = quiescenceSearch(newBoard, alpha, currentBeta, true, depth - 1)

                currentBeta = minOf(currentBeta, score)
                if (currentBeta <= alpha) {
                    return alpha
                }
            }
            return currentBeta
        }
    }

    /**
     * Order moves to improve alpha-beta pruning efficiency
     * Priority: TT move > Captures > Non-captures
     * Optimized to avoid expensive operations
     */
    private fun orderMoves(board: Board, moves: List<Move>, ttMove: Move? = null): List<Move> {
        return moves.sortedWith(compareByDescending<Move> { move ->
            // Highest priority: transposition table move
            if (ttMove != null && move == ttMove) return@compareByDescending 10000

            var score = 0

            // Captures: MVV-LVA (Most Valuable Victim - Least Valuable Attacker)
            if (move.capturedPiece != null) {
                score += move.capturedPiece.type.baseValue * 10
                score -= move.piece.type.baseValue / 10
            }

            // Center control for non-captures
            if (move.to.col in 3..5 && move.to.row in 3..6) {
                score += 5
            }

            // Forward moves for non-captures
            if (move.capturedPiece == null) {
                if (move.piece.color == PieceColor.RED && move.to.row > move.from.row) {
                    score += 2
                } else if (move.piece.color == PieceColor.BLACK && move.to.row < move.from.row) {
                    score += 2
                }
            }

            score
        })
    }

    fun clearCache() {
        transpositionTable.clear()
    }

    fun getCacheSize(): Int = transpositionTable.size()
}
