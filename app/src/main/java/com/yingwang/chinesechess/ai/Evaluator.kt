package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*

/**
 * Enhanced evaluation function for Chinese chess.
 * Adds king safety, piece coordination, threats, and
 * removes expensive mobility calculation from leaf nodes.
 */
object Evaluator {

    // Piece-square tables — from BLACK's perspective (row 0 = black side)
    // Mirrored for RED pieces

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
     * Evaluate the board from RED's perspective.
     * Positive = RED advantage, negative = BLACK advantage.
     */
    fun evaluate(board: Board): Int {
        if (board.isCheckmate()) {
            return if (board.currentPlayer == PieceColor.RED) -90000 else 90000
        }
        if (board.isStalemate()) return 0

        var score = 0

        // Material + positional (piece-square tables)
        for (piece in board.getAllPieces()) {
            val value = getPieceValue(piece)
            score += if (piece.color == PieceColor.RED) value else -value
        }

        // King safety
        score += evaluateKingSafety(board)

        // Piece coordination and threats
        score += evaluateThreats(board)

        // Chariot on open file bonus
        score += evaluateChariotActivity(board)

        // Connected horses bonus
        score += evaluateHorseCoordination(board)

        // Check bonus (tempo advantage)
        if (board.isInCheck(PieceColor.RED)) score -= 30
        if (board.isInCheck(PieceColor.BLACK)) score += 30

        return score
    }

    private fun getPieceValue(piece: Piece): Int {
        return piece.type.baseValue + getPositionalValue(piece)
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
        val row = if (piece.color == PieceColor.RED) piece.position.row else 9 - piece.position.row
        return table[row][piece.position.col]
    }

    /**
     * King safety: penalize exposed king (missing advisors/elephants)
     */
    private fun evaluateKingSafety(board: Board): Int {
        var score = 0

        for (color in listOf(PieceColor.RED, PieceColor.BLACK)) {
            val pieces = board.getPiecesByColor(color)
            val advisorCount = pieces.count { it.type == PieceType.ADVISOR }
            val elephantCount = pieces.count { it.type == PieceType.ELEPHANT }

            // Penalize missing defenders
            var safety = 0
            if (advisorCount == 0) safety -= 40
            else if (advisorCount == 1) safety -= 15
            if (elephantCount == 0) safety -= 25
            else if (elephantCount == 1) safety -= 10

            // Bonus for general staying in center of palace
            val general = pieces.find { it.type == PieceType.GENERAL }
            if (general != null) {
                val col = general.position.col
                if (col == 4) safety += 10 // center column is safest
            }

            score += if (color == PieceColor.RED) safety else -safety
        }

        return score
    }

    /**
     * Evaluate threats: pieces attacking opponent's territory get bonus
     */
    private fun evaluateThreats(board: Board): Int {
        var score = 0

        for (piece in board.getAllPieces()) {
            val row = piece.position.row
            val isRed = piece.color == PieceColor.RED

            when (piece.type) {
                PieceType.SOLDIER -> {
                    // Crossed-river soldier is much more valuable
                    val crossed = if (isRed) row <= 4 else row >= 5
                    if (crossed) {
                        val bonus = 20
                        score += if (isRed) bonus else -bonus
                        // Center crossed soldier even more valuable
                        if (piece.position.col in 3..5) {
                            score += if (isRed) 15 else -15
                        }
                    }
                }
                PieceType.HORSE -> {
                    // Horse in opponent's territory
                    val inEnemyTerritory = if (isRed) row <= 4 else row >= 5
                    if (inEnemyTerritory) {
                        score += if (isRed) 15 else -15
                    }
                    // Horse near opponent's general (卧槽马)
                    val nearGeneral = if (isRed) row <= 2 else row >= 7
                    if (nearGeneral && piece.position.col in 2..6) {
                        score += if (isRed) 25 else -25
                    }
                }
                PieceType.CHARIOT -> {
                    // Chariot penetration into opponent's base rows
                    val deepPenetration = if (isRed) row <= 2 else row >= 7
                    if (deepPenetration) {
                        score += if (isRed) 20 else -20
                    }
                }
                PieceType.CANNON -> {
                    // Cannon value decreases in endgame (fewer pieces to jump over)
                    val totalPieces = board.getAllPieces().size
                    if (totalPieces <= 10) {
                        score += if (isRed) -30 else 30 // cannon less useful
                    }
                }
                else -> {}
            }
        }

        return score
    }

    /**
     * Chariot on open file (no own pawns blocking) gets bonus
     */
    private fun evaluateChariotActivity(board: Board): Int {
        var score = 0

        for (piece in board.getAllPieces()) {
            if (piece.type != PieceType.CHARIOT) continue

            val col = piece.position.col
            val isRed = piece.color == PieceColor.RED

            // Count own soldiers on same column
            val ownSoldiersOnFile = board.getPiecesByColor(piece.color).count {
                it.type == PieceType.SOLDIER && it.position.col == col
            }

            if (ownSoldiersOnFile == 0) {
                score += if (isRed) 15 else -15 // open file bonus
            }

            // Bottom rank chariot (haven't moved) penalty
            val onHomeRank = if (isRed) piece.position.row == 9 else piece.position.row == 0
            if (onHomeRank) {
                score += if (isRed) -20 else 20 // penalty for undeveloped chariot
            }
        }

        return score
    }

    /**
     * Connected/protected horses get bonus
     */
    private fun evaluateHorseCoordination(board: Board): Int {
        var score = 0

        for (color in listOf(PieceColor.RED, PieceColor.BLACK)) {
            val horses = board.getPiecesByColor(color).filter { it.type == PieceType.HORSE }
            if (horses.size == 2) {
                val h1 = horses[0].position
                val h2 = horses[1].position
                val dist = Math.abs(h1.row - h2.row) + Math.abs(h1.col - h2.col)
                // Connected horses (close together) can support each other
                if (dist in 2..4) {
                    score += if (color == PieceColor.RED) 10 else -10
                }
            }
        }

        return score
    }
}
