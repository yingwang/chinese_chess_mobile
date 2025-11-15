package com.yingwang.chinesechess.model

/**
 * Represents a position on the Chinese chess board.
 * Row 0-9 from top to bottom, Col 0-8 from left to right.
 */
data class Position(val row: Int, val col: Int) {
    fun isValid(): Boolean = row in 0..9 && col in 0..8

    override fun toString(): String = "($row,$col)"
}
