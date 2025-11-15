package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*

/**
 * Professional-level evaluation function for Chinese chess
 * Uses piece-square tables and positional analysis
 */
object Evaluator {

    // Piece-square tables for positional evaluation
    // Values are from RED's perspective (higher row numbers = RED side)

    private val GENERAL_TABLE = arrayOf(
        intArrayOf(0, 0, 0, 8, 9, 8, 0, 0, 0),
        intArrayOf(0, 0, 0, 9, 9, 9, 0, 0, 0),
        intArrayOf(0, 0, 0, 8, 9, 8, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 8, 9, 8, 0, 0, 0),
        intArrayOf(0, 0, 0, 9, 9, 9, 0, 0, 0),
        intArrayOf(0, 0, 0, 8, 9, 8, 0, 0, 0)
    )

    private val ADVISOR_TABLE = arrayOf(
        intArrayOf(0, 0, 0, 20, 0, 20, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 23, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 20, 0, 20, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 20, 0, 20, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 23, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 20, 0, 20, 0, 0, 0)
    )

    private val ELEPHANT_TABLE = arrayOf(
        intArrayOf(0, 0, 20, 0, 0, 0, 20, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(18, 0, 0, 0, 23, 0, 0, 0, 18),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 20, 0, 0, 0, 20, 0, 0),
        intArrayOf(0, 0, 20, 0, 0, 0, 20, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(18, 0, 0, 0, 23, 0, 0, 0, 18),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 20, 0, 0, 0, 20, 0, 0)
    )

    private val HORSE_TABLE = arrayOf(
        intArrayOf(90, 90, 90, 96, 90, 96, 90, 90, 90),
        intArrayOf(90, 96, 103, 97, 94, 97, 103, 96, 90),
        intArrayOf(92, 98, 99, 103, 99, 103, 99, 98, 92),
        intArrayOf(93, 108, 100, 107, 100, 107, 100, 108, 93),
        intArrayOf(90, 100, 99, 103, 104, 103, 99, 100, 90),
        intArrayOf(90, 98, 101, 102, 103, 102, 101, 98, 90),
        intArrayOf(92, 94, 98, 95, 98, 95, 98, 94, 92),
        intArrayOf(93, 92, 94, 95, 92, 95, 94, 92, 93),
        intArrayOf(85, 90, 92, 93, 78, 93, 92, 90, 85),
        intArrayOf(88, 85, 90, 88, 90, 88, 90, 85, 88)
    )

    private val CHARIOT_TABLE = arrayOf(
        intArrayOf(206, 208, 207, 213, 214, 213, 207, 208, 206),
        intArrayOf(206, 212, 209, 216, 233, 216, 209, 212, 206),
        intArrayOf(206, 208, 207, 214, 216, 214, 207, 208, 206),
        intArrayOf(206, 213, 213, 216, 216, 216, 213, 213, 206),
        intArrayOf(208, 211, 211, 214, 215, 214, 211, 211, 208),
        intArrayOf(208, 212, 212, 214, 215, 214, 212, 212, 208),
        intArrayOf(204, 209, 204, 212, 214, 212, 204, 209, 204),
        intArrayOf(198, 208, 204, 212, 212, 212, 204, 208, 198),
        intArrayOf(200, 208, 206, 212, 200, 212, 206, 208, 200),
        intArrayOf(194, 206, 204, 212, 200, 212, 204, 206, 194)
    )

    private val CANNON_TABLE = arrayOf(
        intArrayOf(100, 100, 96, 91, 90, 91, 96, 100, 100),
        intArrayOf(98, 98, 96, 92, 89, 92, 96, 98, 98),
        intArrayOf(97, 97, 96, 91, 92, 91, 96, 97, 97),
        intArrayOf(96, 99, 99, 98, 100, 98, 99, 99, 96),
        intArrayOf(96, 96, 96, 96, 100, 96, 96, 96, 96),
        intArrayOf(95, 96, 99, 96, 100, 96, 99, 96, 95),
        intArrayOf(96, 96, 96, 96, 96, 96, 96, 96, 96),
        intArrayOf(97, 96, 100, 99, 101, 99, 100, 96, 97),
        intArrayOf(96, 97, 98, 98, 98, 98, 98, 97, 96),
        intArrayOf(96, 96, 97, 99, 99, 99, 97, 96, 96)
    )

    private val SOLDIER_TABLE = arrayOf(
        intArrayOf(9, 9, 9, 11, 13, 11, 9, 9, 9),
        intArrayOf(19, 24, 34, 42, 44, 42, 34, 24, 19),
        intArrayOf(19, 24, 32, 37, 37, 37, 32, 24, 19),
        intArrayOf(19, 23, 27, 29, 30, 29, 27, 23, 19),
        intArrayOf(14, 18, 20, 27, 29, 27, 20, 18, 14),
        intArrayOf(7, 0, 13, 0, 16, 0, 13, 0, 7),
        intArrayOf(7, 0, 7, 0, 15, 0, 7, 0, 7),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0),
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    /**
     * Evaluate the board from RED's perspective
     * Positive scores favor RED, negative scores favor BLACK
     */
    fun evaluate(board: Board): Int {
        var score = 0

        for (piece in board.getAllPieces()) {
            val pieceValue = getPieceValue(piece)
            score += if (piece.color == PieceColor.RED) pieceValue else -pieceValue
        }

        // Add mobility bonus
        score += evaluateMobility(board)

        // Add control of center
        score += evaluateCenterControl(board)

        // Check if in checkmate or check
        if (board.isCheckmate()) {
            return if (board.currentPlayer == PieceColor.RED) Int.MIN_VALUE + 1 else Int.MAX_VALUE - 1
        }

        if (board.isInCheck(PieceColor.RED)) {
            score -= 50
        }
        if (board.isInCheck(PieceColor.BLACK)) {
            score += 50
        }

        return score
    }

    private fun getPieceValue(piece: Piece): Int {
        val baseValue = piece.type.baseValue
        val positionalValue = getPositionalValue(piece)
        return baseValue + positionalValue
    }

    private fun getPositionalValue(piece: Piece): Int {
        val table = when (piece.type) {
            PieceType.GENERAL -> GENERAL_TABLE
            PieceType.ADVISOR -> ADVISOR_TABLE
            PieceType.ELEPHANT -> ELEPHANT_TABLE
            PieceType.HORSE -> HORSE_TABLE
            PieceType.CHARIOT -> CHARIOT_TABLE
            PieceType.CANNON -> CANNON_TABLE
            PieceType.SOLDIER -> SOLDIER_TABLE
        }

        val row = if (piece.color == PieceColor.RED) {
            piece.position.row
        } else {
            9 - piece.position.row // Mirror for BLACK
        }

        return table[row][piece.position.col]
    }

    private fun evaluateMobility(board: Board): Int {
        var redMobility = 0
        var blackMobility = 0

        for (piece in board.getPiecesByColor(PieceColor.RED)) {
            redMobility += piece.getLegalMoves(board).size
        }

        for (piece in board.getPiecesByColor(PieceColor.BLACK)) {
            blackMobility += piece.getLegalMoves(board).size
        }

        return (redMobility - blackMobility) * 2
    }

    private fun evaluateCenterControl(board: Board): Int {
        var score = 0
        val centerCols = 3..5

        for (piece in board.getAllPieces()) {
            if (piece.position.col in centerCols) {
                val bonus = when (piece.type) {
                    PieceType.HORSE, PieceType.CHARIOT, PieceType.CANNON -> 5
                    PieceType.SOLDIER -> 3
                    else -> 0
                }
                score += if (piece.color == PieceColor.RED) bonus else -bonus
            }
        }

        return score
    }
}
