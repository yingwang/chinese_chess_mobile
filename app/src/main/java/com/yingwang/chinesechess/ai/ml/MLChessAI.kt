package com.yingwang.chinesechess.ai.ml

import android.content.Context
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.exp
import kotlin.math.ln

/**
 * ML-based Chinese Chess engine using MCTS guided by a TFLite neural network.
 *
 * Drop-in replacement for [com.yingwang.chinesechess.ai.ChessAI] — exposes the
 * same `suspend fun findBestMove(board, moveHistory)` interface so
 * [com.yingwang.chinesechess.GameController] can switch between engines.
 */
class MLChessAI(
    context: Context,
    private val numSimulations: Int = 200,
    private val cPuct: Float = 1.5f
) : Closeable {

    private val model = TFLiteModel(context)

    /**
     * Run MCTS and return the best move for the current player, or null if no
     * legal moves exist.
     */
    suspend fun findBestMove(
        board: Board,
        moveHistory: List<Move> = emptyList()
    ): Move? = withContext(Dispatchers.Default) {
        val legalMoves = board.getAllLegalMoves()
        if (legalMoves.isEmpty()) return@withContext null

        val root = MCTSNode(move = null, parent = null, priorProbability = 0f)
        expandNode(root, board, legalMoves)

        // Run simulations
        for (i in 0 until numSimulations) {
            if (!isActive) break
            var node = root
            val scratchBoard = board.copy()

            // 1. Select — descend the tree picking highest UCB child
            while (!node.isLeaf) {
                node = node.selectChild(cPuct)
                scratchBoard.makeMoveInPlace(node.move!!)
            }

            // 2. Evaluate leaf (or expand if not terminal)
            val value: Float
            if (scratchBoard.isCheckmate()) {
                // Current player is checkmated → loss for them = win for parent
                value = -1f
            } else if (scratchBoard.isStalemate()) {
                value = 0f
            } else {
                val childMoves = scratchBoard.getAllLegalMoves()
                expandNode(node, scratchBoard, childMoves)
                val tensor = MoveEncoding.boardToTensor(scratchBoard)
                val (_, v) = model.predict(tensor)
                value = v
            }

            // 3. Backpropagate
            node.backpropagate(value)
        }

        // Pick move: use temperature-controlled sampling
        val temperature = moveTemperature(moveHistory.size)
        selectMoveFromRoot(root, temperature)
    }

    /**
     * Expand a leaf node: create one child per legal move with prior
     * probabilities from the neural network policy head.
     */
    private fun expandNode(node: MCTSNode, board: Board, legalMoves: List<Move>) {
        val tensor = MoveEncoding.boardToTensor(board)
        val (policyLogits, _) = model.predict(tensor)

        // Gather logits for legal moves only, then softmax over them
        val legalIndices = IntArray(legalMoves.size)
        val legalLogits = FloatArray(legalMoves.size)
        for (i in legalMoves.indices) {
            val m = legalMoves[i]
            val idx = MoveEncoding.moveToIndex(m.from, m.to)
            legalIndices[i] = idx
            legalLogits[i] = if (idx >= 0) policyLogits[idx] else -1e9f
        }

        val priors = softmax(legalLogits)

        for (i in legalMoves.indices) {
            val child = MCTSNode(
                move = legalMoves[i],
                parent = node,
                priorProbability = priors[i]
            )
            node.children[legalMoves[i]] = child
        }
    }

    /**
     * Select the move to play from the root node.
     *
     * With temperature > 0, sample proportionally to visit_count^(1/temp).
     * With temperature ~0, pick the most-visited move (deterministic).
     */
    private fun selectMoveFromRoot(root: MCTSNode, temperature: Float): Move? {
        if (root.children.isEmpty()) return null

        if (temperature < 0.01f) {
            // Greedy: pick most visited
            return root.children.maxBy { it.value.visitCount }.key
        }

        val entries = root.children.entries.toList()
        val invTemp = 1.0f / temperature
        val weights = FloatArray(entries.size) { i ->
            Math.pow(entries[i].value.visitCount.toDouble(), invTemp.toDouble()).toFloat()
        }
        val sum = weights.sum()
        if (sum <= 0f) {
            return entries.maxBy { it.value.visitCount }.key
        }
        for (i in weights.indices) weights[i] /= sum

        // Sample
        val r = Math.random().toFloat()
        var cumulative = 0f
        for (i in weights.indices) {
            cumulative += weights[i]
            if (r < cumulative) return entries[i].key
        }
        return entries.last().key
    }

    /**
     * Temperature schedule: higher early (exploration), lower later (exploitation).
     */
    private fun moveTemperature(moveCount: Int): Float = when {
        moveCount < 20 -> 1.0f
        moveCount < 40 -> 0.5f
        else -> 0.1f
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    override fun close() {
        model.close()
    }
}
