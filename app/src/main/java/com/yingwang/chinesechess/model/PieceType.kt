package com.yingwang.chinesechess.model

/**
 * Types of Chinese chess pieces
 */
enum class PieceType(val chineseName: String, val baseValue: Int) {
    GENERAL("将/帅", 10000),    // King/General
    ADVISOR("士/仕", 120),      // Advisor/Guard
    ELEPHANT("象/相", 120),     // Elephant/Minister
    HORSE("马/馬", 400),        // Horse/Knight
    CHARIOT("车/車", 900),      // Chariot/Rook
    CANNON("炮/砲", 450),       // Cannon
    SOLDIER("兵/卒", 100);      // Soldier/Pawn

    fun getDisplayName(color: PieceColor): String {
        return when (this) {
            GENERAL -> if (color == PieceColor.RED) "帅" else "将"
            ADVISOR -> if (color == PieceColor.RED) "仕" else "士"
            ELEPHANT -> if (color == PieceColor.RED) "相" else "象"
            HORSE -> "馬"
            CHARIOT -> "車"
            CANNON -> "砲"
            SOLDIER -> if (color == PieceColor.RED) "兵" else "卒"
        }
    }
}
