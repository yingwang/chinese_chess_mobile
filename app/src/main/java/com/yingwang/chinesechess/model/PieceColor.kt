package com.yingwang.chinesechess.model

enum class PieceColor {
    RED,    // Red side (bottom), moves first
    BLACK;  // Black side (top)

    fun opposite(): PieceColor = if (this == RED) BLACK else RED
}
