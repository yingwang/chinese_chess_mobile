package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*

/**
 * Opening book for common Chinese chess openings
 * Provides quick responses for the first few moves
 */
object OpeningBook {

    // Common opening patterns stored as move sequences
    // Format: "from_row,from_col,to_row,to_col"
    private val openingMoves = mapOf(
        // Red's first move options (popular openings)
        listOf<String>() to listOf(
            "2,1,4,2",  // 炮二平五 (Center Cannon)
            "2,7,4,6",  // 炮八平五 (Center Cannon)
            "0,1,2,2",  // 马二进三 (Horse opening)
            "0,7,2,6",  // 马八进七 (Horse opening)
        ),

        // Response to Center Cannon (炮二平五)
        listOf("2,1,4,2") to listOf(
            "7,7,5,6",  // 马8进7 (Screen Horse Defense)
            "7,1,5,2",  // 马2进3 (Screen Horse Defense)
            "7,7,5,8",  // 马8进9 (Edge Horse Defense)
        ),

        // Response to Center Cannon (炮八平五)
        listOf("2,7,4,6") to listOf(
            "7,7,5,6",  // 马8进7 (Screen Horse Defense)
            "7,1,5,2",  // 马2进3 (Screen Horse Defense)
        ),

        // Red's second move after Center Cannon + Black Screen Horse
        listOf("2,1,4,2", "7,7,5,6") to listOf(
            "0,1,2,2",  // 马二进三
            "0,7,2,6",  // 马八进七
        ),

        // Response to Horse opening (马二进三)
        listOf("0,1,2,2") to listOf(
            "7,7,5,6",  // 马8进7
            "7,1,5,2",  // 马2进3
        ),

        // Symmetrical responses for common patterns
        listOf("2,1,4,2", "7,7,5,6", "0,1,2,2") to listOf(
            "7,1,5,2",  // 马2进3
            "9,0,8,0",  // 车9平8
        ),
    )

    /**
     * Try to find an opening move based on the move history
     * Returns null if no opening move is found
     */
    fun getOpeningMove(moveHistory: List<Move>): Move? {
        // Only use opening book for first 6 moves
        if (moveHistory.size >= 6) return null

        // Convert move history to string format
        val historyKey = moveHistory.map { move ->
            "${move.from.row},${move.from.col},${move.to.row},${move.to.col}"
        }

        // Look up opening response
        val responses = openingMoves[historyKey]
        if (responses != null && responses.isNotEmpty()) {
            // Pick a random response for variety
            val selectedMove = responses.random()
            return parseMove(selectedMove)
        }

        return null
    }

    /**
     * Parse a move string into a Move object
     * Note: This returns a simplified move without piece information
     * The actual move will be matched against legal moves in the board
     */
    private fun parseMove(moveStr: String): Move? {
        try {
            val parts = moveStr.split(",").map { it.toInt() }
            if (parts.size != 4) return null

            return Move(
                piece = Piece(PieceType.GENERAL, PieceColor.RED, Position(0, 0)), // Dummy piece
                from = Position(parts[0], parts[1]),
                to = Position(parts[2], parts[3]),
                capturedPiece = null
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Match an opening book move with an actual legal move from the board
     */
    fun matchOpeningMove(bookMove: Move, legalMoves: List<Move>): Move? {
        return legalMoves.find { move ->
            move.from.row == bookMove.from.row &&
            move.from.col == bookMove.from.col &&
            move.to.row == bookMove.to.row &&
            move.to.col == bookMove.to.col
        }
    }
}
