package com.yingwang.chinesechess.ai.ml

import com.yingwang.chinesechess.model.Move

/**
 * Node in the Monte Carlo Tree Search tree.
 *
 * Each node corresponds to a board state reached by playing [move] from the parent.
 * The prior probability comes from the neural network policy head.
 */
class MCTSNode(
    val move: Move?,
    val parent: MCTSNode?,
    val priorProbability: Float
) {
    var visitCount: Int = 0
        private set
    var totalValue: Float = 0f
        private set
    val children: MutableMap<Move, MCTSNode> = mutableMapOf()

    /** Mean action-value Q(s,a). */
    val qValue: Float
        get() = if (visitCount == 0) 0f else totalValue / visitCount

    /**
     * Upper confidence bound score used for tree traversal.
     *
     * UCB(s,a) = Q(s,a) + cPuct * P(s,a) * sqrt(parentVisits) / (1 + N(s,a))
     */
    fun ucbScore(cPuct: Float, parentVisits: Int): Float {
        val exploration = cPuct * priorProbability *
                Math.sqrt(parentVisits.toDouble()).toFloat() / (1 + visitCount)
        return qValue + exploration
    }

    /** Back-propagate a value estimate up to the root. */
    fun backpropagate(value: Float) {
        visitCount++
        totalValue += value
        // Parent sees the negated value (opponent's perspective)
        parent?.backpropagate(-value)
    }

    val isLeaf: Boolean get() = children.isEmpty()

    /** Select the child with the highest UCB score. */
    fun selectChild(cPuct: Float): MCTSNode {
        return children.values.maxBy { it.ucbScore(cPuct, visitCount) }
    }
}
