package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*

/**
 * Opening book for common Chinese chess openings
 * Provides quick responses for the first few moves
 */
object OpeningBook {

    // Common opening patterns stored as move sequences
    // Format: "from_row,from_col,to_row,to_col"
    // Board coordinate system: Row 0-2 is BLACK (top), Row 7-9 is RED (bottom)
    // RED moves first
    private val openingMoves = mapOf(
        // Red's first move options (popular openings)
        listOf<String>() to listOf(
            "7,1,7,4",  // 炮二平五 (Center Cannon - left cannon to center)
            "7,7,7,4",  // 炮八平五 (Center Cannon - right cannon to center)
            "9,1,7,2",  // 马二进三 (Horse opening - left horse forward)
            "9,7,7,6",  // 马八进七 (Horse opening - right horse forward)
            "6,4,5,4",  // 兵五进一 (Center pawn forward)
        ),

        // Black's response to Center Cannon (炮二平五)
        listOf("7,1,7,4") to listOf(
            "0,7,2,6",  // 马8进7 (Screen Horse Defense - right horse)
            "0,1,2,2",  // 马2进3 (Screen Horse Defense - left horse)
            "2,7,2,4",  // 炮8平5 (Counter Center Cannon)
            "3,4,2,4",  // 卒5进1 (Center pawn forward)
        ),

        // Black's response to Center Cannon (炮八平五)
        listOf("7,7,7,4") to listOf(
            "0,7,2,6",  // 马8进7 (Screen Horse Defense)
            "0,1,2,2",  // 马2进3 (Screen Horse Defense)
            "2,1,2,4",  // 炮2平5 (Counter Center Cannon)
        ),

        // Red's second move after Center Cannon + Black Screen Horse
        listOf("7,1,7,4", "0,7,2,6") to listOf(
            "9,1,7,2",  // 马二进三 (Develop left horse)
            "9,7,7,6",  // 马八进七 (Develop right horse)
            "7,7,7,6",  // 炮八平七 (Position right cannon)
        ),

        // Red's second move after Center Cannon + Black left Screen Horse
        listOf("7,1,7,4", "0,1,2,2") to listOf(
            "9,7,7,6",  // 马八进七 (Develop right horse)
            "9,1,7,2",  // 马二进三 (Develop left horse)
            "7,7,7,2",  // 炮八平三 (Position right cannon)
        ),

        // Black's response to Horse opening (马二进三)
        listOf("9,1,7,2") to listOf(
            "0,7,2,6",  // 马8进7 (Defend with horse)
            "0,1,2,2",  // 马2进3 (Mirror response)
            "2,7,5,7",  // 炮8进3 (Advance right cannon)
        ),

        // Black's response to Horse opening (马八进七)
        listOf("9,7,7,6") to listOf(
            "0,1,2,2",  // 马2进3 (Defend with horse)
            "0,7,2,6",  // 马8进7 (Mirror response)
            "2,1,5,1",  // 炮2进3 (Advance left cannon)
        ),

        // Red's third move in classic Cannon + Horse opening
        listOf("7,1,7,4", "0,7,2,6", "9,1,7,2") to listOf(
            "9,0,8,0",  // 车一平二 (Develop left chariot)
            "7,7,6,7",  // 炮八进一 (Advance right cannon)
            "6,2,5,2",  // 兵三进一 (Advance left pawn)
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
