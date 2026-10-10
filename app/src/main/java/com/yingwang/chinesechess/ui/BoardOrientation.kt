package com.yingwang.chinesechess.ui

import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Position

/**
 * Where a square of the board is drawn. Normally red sits at the bottom and a square's grid
 * cell is its own row and column. Turned round ([flipped]), for a player on black in an online
 * game, the board is rotated half a turn: black's back rank at the bottom, columns mirrored.
 * Only the drawing and the touch turn round; the model, and what goes to the other player, keep
 * red's orientation.
 */
object BoardOrientation {
    /** The grid cell (row from the top, column from the left) where [pos] is drawn. */
    fun toScreen(pos: Position, flipped: Boolean): Position =
        if (flipped) Position(Board.ROWS - 1 - pos.row, Board.COLS - 1 - pos.col) else pos

    /** The square drawn at grid cell ([row], [col]); the rotation is its own inverse. */
    fun fromScreen(row: Int, col: Int, flipped: Boolean): Position =
        toScreen(Position(row, col), flipped)
}
