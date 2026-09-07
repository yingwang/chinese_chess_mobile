package com.yingwang.chinesechess

import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Move
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.PieceType
import kotlin.math.abs

/**
 * Standard four-character notation (四字记录法), e.g. 炮二平五, 馬八进七, 前車退一.
 *
 * Needs the board as it was before the move: 前/后 is decided by whether another
 * piece of the same kind stands on the same file. Red reads files 九…一 from
 * left to right and numbers in Chinese; black reads 1…9 and uses full-width digits. Pieces
 * that move diagonally (馬, 相/象, 仕/士) name the destination file after 进/退,
 * everything else names the number of ranks travelled.
 */
object MoveNotation {

    private val RED_FILES = listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
    // Full-width digits so black's moves sit on the same grid as red's in the move list.
    private val BLACK_FILES = listOf("１", "２", "３", "４", "５", "６", "７", "８", "９")
    private val RED_STEPS = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val BLACK_STEPS = listOf("", "１", "２", "３", "４", "５", "６", "７", "８", "９")
    private val DIAGONAL = setOf(PieceType.HORSE, PieceType.ELEPHANT, PieceType.ADVISOR)

    fun format(move: Move, boardBefore: Board): String {
        val piece = move.piece
        val isRed = piece.color == PieceColor.RED
        val name = piece.type.getDisplayName(piece.color)
        val files = if (isRed) RED_FILES else BLACK_FILES
        val steps = if (isRed) RED_STEPS else BLACK_STEPS
        val rowDiff = move.to.row - move.from.row
        // Red sits at the bottom, so "forward" is up the screen for red and down for black.
        val forward = if (isRed) rowDiff < 0 else rowDiff > 0

        val sameFile = boardBefore.getAllPieces()
            .filter { it.color == piece.color && it.type == piece.type && it.position.col == move.from.col }
            .sortedBy { it.position.row }
        val head = if (sameFile.size >= 2 && piece.type != PieceType.GENERAL) {
            // Index 0 is the piece nearest the top of the screen; for red that is the front one.
            val idx = sameFile.indexOfFirst { it.position == move.from }
            val rank = if (isRed) idx else sameFile.size - 1 - idx
            val label = when {
                rank == 0 -> "前"
                rank == sameFile.size - 1 -> "后"
                else -> "中"
            }
            label + name
        } else {
            name + files[move.from.col]
        }

        return when {
            rowDiff == 0 -> head + "平" + files[move.to.col]
            piece.type in DIAGONAL -> head + (if (forward) "进" else "退") + files[move.to.col]
            else -> head + (if (forward) "进" else "退") + steps[abs(rowDiff)]
        }
    }

    /** Notation for every move of a game, replayed from [initialBoard]. */
    fun formatAll(moves: List<Move>, initialBoard: Board): List<String> {
        val board = initialBoard.copy()
        return moves.map { move ->
            val text = format(move, board)
            board.makeMoveInPlace(move)
            text
        }
    }
}
