package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*

/**
 * Enhanced Chinese Chess AI using:
 * - Alpha-beta pruning with aspiration windows
 * - Iterative deepening
 * - Transposition tables with Zobrist hashing
 * - Null move pruning
 * - Late move reductions (LMR)
 * - Killer move heuristic
 * - History heuristic
 * - MVV-LVA move ordering
 * - Quiescence search
 * - Opening book
 */
class ChessAI(
    private val maxDepth: Int = 6,
    private val timeLimit: Long = 5000,
    private val quiescenceDepth: Int = 3
) {
    private val transpositionTable = TranspositionTable()
    private var nodesSearched = 0
    private var startTime = 0L
    private var shouldStop = false

    // Killer moves: 2 per depth level
    private val killerMoves = Array(30) { arrayOfNulls<Move>(2) }

    // History heuristic: [color][fromRow*9+fromCol][toRow*9+toCol]
    private val historyTable = Array(2) { Array(90) { IntArray(90) } }

    companion object {
        private const val INFINITY = 1_000_000
        private const val CHECKMATE_SCORE = 100_000
        private const val NULL_MOVE_REDUCTION = 2
        private const val LMR_THRESHOLD = 4 // reduce after this many moves
        private const val ASPIRATION_WINDOW = 50
    }

    suspend fun findBestMove(board: Board, moveHistory: List<Move> = emptyList()): Move? = withContext(Dispatchers.Default) {
        nodesSearched = 0
        startTime = System.currentTimeMillis()
        shouldStop = false
        for (i in killerMoves.indices) killerMoves[i].fill(null)
        // Decay history table instead of clearing (preserves knowledge)
        for (c in historyTable.indices) for (f in historyTable[c].indices) for (t in historyTable[c][f].indices) {
            historyTable[c][f][t] = historyTable[c][f][t] / 2
        }

        // Opening book
        if (moveHistory.size < 6) {
            val openingMove = OpeningBook.getOpeningMove(moveHistory)
            if (openingMove != null) {
                val legalMoves = board.getAllLegalMoves()
                val matchedMove = OpeningBook.matchOpeningMove(openingMove, legalMoves)
                if (matchedMove != null) return@withContext matchedMove
            }
        }

        var bestMove: Move? = null
        var bestScore = 0

        // Iterative deepening with aspiration windows
        for (depth in 1..maxDepth) {
            if (shouldStop) break

            val result = if (depth >= 4 && bestMove != null) {
                // Aspiration window search
                var alpha = bestScore - ASPIRATION_WINDOW
                var beta = bestScore + ASPIRATION_WINDOW
                var res = searchRoot(board, depth, alpha, beta)

                // Re-search with full window if failed
                if (res != null && (res.second <= alpha || res.second >= beta)) {
                    res = searchRoot(board, depth, -INFINITY, INFINITY)
                }
                res
            } else {
                searchRoot(board, depth, -INFINITY, INFINITY)
            }

            if (result != null && !shouldStop) {
                bestMove = result.first
                bestScore = result.second

                val elapsed = System.currentTimeMillis() - startTime
                println("Depth $depth: score=$bestScore, nodes=$nodesSearched, time=${elapsed}ms")

                if (Math.abs(bestScore) > CHECKMATE_SCORE - 100) break
            }

            if (System.currentTimeMillis() - startTime > timeLimit) {
                shouldStop = true
            }
        }

        bestMove
    }

    private fun searchRoot(board: Board, depth: Int, alpha: Int, beta: Int): Pair<Move, Int>? {
        var bestMove: Move? = null
        val isMaximizing = board.currentPlayer == PieceColor.RED
        var bestScore = if (isMaximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var currentAlpha = alpha
        var currentBeta = beta

        val ttEntry = transpositionTable.probe(board.getPositionHash())
        val moves = orderMoves(board, board.getAllLegalMoves(), ttEntry?.bestMove, depth)
        if (moves.isEmpty()) return null

        for (move in moves) {
            if (shouldStop) break

            val newBoard = board.makeMove(move)
            newBoard.currentPlayer = board.currentPlayer.opposite()

            val score = alphaBeta(newBoard, depth - 1, currentAlpha, currentBeta, !isMaximizing, true)

            if (isMaximizing) {
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
                currentAlpha = maxOf(currentAlpha, score)
            } else {
                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }
                currentBeta = minOf(currentBeta, score)
            }
        }

        return if (bestMove != null) Pair(bestMove, bestScore) else null
    }

    private fun alphaBeta(
        board: Board,
        depth: Int,
        alpha: Int,
        beta: Int,
        maximizing: Boolean,
        allowNullMove: Boolean
    ): Int {
        nodesSearched++

        if (nodesSearched % 2048 == 0) {
            if (System.currentTimeMillis() - startTime > timeLimit) {
                shouldStop = true
            }
        }
        if (shouldStop) return 0

        // Transposition table lookup
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

        // Leaf node
        if (depth == 0) {
            return if (quiescenceDepth == 0) Evaluator.evaluate(board)
            else quiescenceSearch(board, alpha, beta, maximizing, quiescenceDepth)
        }

        if (board.isCheckmate()) {
            return if (maximizing) -CHECKMATE_SCORE + (maxDepth - depth)
            else CHECKMATE_SCORE - (maxDepth - depth)
        }
        if (board.isStalemate()) return 0

        // Null move pruning: skip our turn and see if opponent can still not beat beta
        // Don't do null move when in check or at shallow depth
        if (allowNullMove && depth >= 3 && !board.isInCheck(board.currentPlayer)) {
            val nullBoard = board.copy()
            nullBoard.currentPlayer = board.currentPlayer.opposite()
            val nullScore = alphaBeta(nullBoard, depth - 1 - NULL_MOVE_REDUCTION, alpha, beta, !maximizing, false)

            if (maximizing && nullScore >= beta) return beta
            if (!maximizing && nullScore <= alpha) return alpha
        }

        val moves = orderMoves(board, board.getAllLegalMoves(), ttEntry?.bestMove, depth)
        if (moves.isEmpty()) return Evaluator.evaluate(board)

        var currentAlpha = alpha
        var currentBeta = beta
        var bestScore: Int
        var bestMove: Move? = null
        val inCheck = board.isInCheck(board.currentPlayer)

        if (maximizing) {
            bestScore = Int.MIN_VALUE
            for ((moveIndex, move) in moves.withIndex()) {
                if (shouldStop) break

                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                // Late move reduction: search late non-capture moves at reduced depth
                var reduction = 0
                if (moveIndex >= LMR_THRESHOLD && depth >= 3 &&
                    !inCheck && move.capturedPiece == null) {
                    reduction = 1
                }

                var score = alphaBeta(newBoard, depth - 1 - reduction, currentAlpha, currentBeta, false, true)

                // Re-search at full depth if reduced search beats alpha
                if (reduction > 0 && score > currentAlpha) {
                    score = alphaBeta(newBoard, depth - 1, currentAlpha, currentBeta, false, true)
                }

                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }

                currentAlpha = maxOf(currentAlpha, score)
                if (currentBeta <= currentAlpha) {
                    // Beta cutoff — update killer and history
                    if (move.capturedPiece == null) {
                        updateKillerMoves(depth, move)
                        updateHistory(move, depth)
                    }
                    transpositionTable.store(hash, depth, bestScore, TranspositionTable.EntryType.LOWER_BOUND, bestMove)
                    return bestScore
                }
            }
        } else {
            bestScore = Int.MAX_VALUE
            for ((moveIndex, move) in moves.withIndex()) {
                if (shouldStop) break

                val newBoard = board.makeMove(move)
                newBoard.currentPlayer = board.currentPlayer.opposite()

                var reduction = 0
                if (moveIndex >= LMR_THRESHOLD && depth >= 3 &&
                    !inCheck && move.capturedPiece == null) {
                    reduction = 1
                }

                var score = alphaBeta(newBoard, depth - 1 - reduction, currentAlpha, currentBeta, true, true)

                if (reduction > 0 && score < currentBeta) {
                    score = alphaBeta(newBoard, depth - 1, currentAlpha, currentBeta, true, true)
                }

                if (score < bestScore) {
                    bestScore = score
                    bestMove = move
                }

                currentBeta = minOf(currentBeta, score)
                if (currentBeta <= currentAlpha) {
                    if (move.capturedPiece == null) {
                        updateKillerMoves(depth, move)
                        updateHistory(move, depth)
                    }
                    transpositionTable.store(hash, depth, bestScore, TranspositionTable.EntryType.UPPER_BOUND, bestMove)
                    return bestScore
                }
            }
        }

        transpositionTable.store(hash, depth, bestScore, TranspositionTable.EntryType.EXACT, bestMove)
        return bestScore
    }

    private fun quiescenceSearch(
        board: Board, alpha: Int, beta: Int,
        maximizing: Boolean, depth: Int
    ): Int {
        nodesSearched++
        if (depth == 0) return Evaluator.evaluate(board)

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
                if (currentAlpha >= beta) return beta
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
                if (currentBeta <= alpha) return alpha
            }
            return currentBeta
        }
    }

    /**
     * Move ordering: TT move > Captures (MVV-LVA) > Killers > History
     */
    private fun orderMoves(board: Board, moves: List<Move>, ttMove: Move? = null, depth: Int = -1): List<Move> {
        return moves.sortedWith(compareByDescending<Move> { move ->
            if (ttMove != null && move == ttMove) return@compareByDescending 100000

            // Killer moves
            if (depth >= 0 && depth < killerMoves.size) {
                if (move == killerMoves[depth][0]) return@compareByDescending 50000
                if (move == killerMoves[depth][1]) return@compareByDescending 45000
            }

            var score = 0

            // Captures: MVV-LVA
            if (move.capturedPiece != null) {
                score += 10000 + move.capturedPiece.type.baseValue * 10 - move.piece.type.baseValue
            }

            // History heuristic for non-captures
            if (move.capturedPiece == null) {
                val colorIdx = move.piece.color.ordinal
                val fromIdx = move.from.row * 9 + move.from.col
                val toIdx = move.to.row * 9 + move.to.col
                score += historyTable[colorIdx][fromIdx][toIdx]
            }

            score
        })
    }

    private fun updateKillerMoves(depth: Int, move: Move) {
        if (depth >= 0 && depth < killerMoves.size) {
            if (killerMoves[depth][0] != move) {
                killerMoves[depth][1] = killerMoves[depth][0]
                killerMoves[depth][0] = move
            }
        }
    }

    private fun updateHistory(move: Move, depth: Int) {
        val colorIdx = move.piece.color.ordinal
        val fromIdx = move.from.row * 9 + move.from.col
        val toIdx = move.to.row * 9 + move.to.col
        historyTable[colorIdx][fromIdx][toIdx] += depth * depth
    }

    fun clearCache() {
        transpositionTable.clear()
        for (c in historyTable.indices) for (f in historyTable[c].indices) historyTable[c][f].fill(0)
    }

    fun getCacheSize(): Int = transpositionTable.size()
}
