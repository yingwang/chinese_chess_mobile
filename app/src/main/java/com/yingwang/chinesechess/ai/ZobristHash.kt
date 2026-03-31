package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.*

object ZobristHash {
    // Random numbers: [color][pieceType][row][col]
    private val table = Array(2) { Array(7) { Array(10) { LongArray(9) } } }
    private val sideToMove: Long

    init {
        val rng = java.util.Random(0xDEADBEEF) // fixed seed for reproducibility
        for (c in 0..1) for (p in 0..6) for (r in 0..9) for (col in 0..8) {
            table[c][p][r][col] = rng.nextLong()
        }
        sideToMove = rng.nextLong()
    }

    fun hash(board: Board): Long {
        var h = 0L
        for (piece in board.getAllPieces()) {
            h = h xor table[piece.color.ordinal][piece.type.ordinal][piece.position.row][piece.position.col]
        }
        if (board.currentPlayer == PieceColor.BLACK) {
            h = h xor sideToMove
        }
        return h
    }
}
