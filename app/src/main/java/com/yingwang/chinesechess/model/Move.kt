package com.yingwang.chinesechess.model

/**
 * Represents a move in Chinese chess
 */
data class Move(
    val from: Position,
    val to: Position,
    val piece: Piece,
    val capturedPiece: Piece?
) {
    fun isCapture(): Boolean = capturedPiece != null

    override fun toString(): String {
        return "${piece.type.chineseName} $from -> $to" +
                if (capturedPiece != null) " captures ${capturedPiece.type.chineseName}" else ""
    }
}
