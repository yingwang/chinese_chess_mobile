package com.yingwang.chinesechess.ai.ml

import com.yingwang.chinesechess.model.*

/**
 * Move encoding for the neural network policy head.
 *
 * Ports encoding.py exactly: same feature plane layout (15, 10, 9),
 * same move enumeration (2086 actions), same index assignment (sorted
 * lexicographically by (from_pos, to_pos)).
 */
object MoveEncoding {

    const val ROWS = 10
    const val COLS = 9
    const val NUM_SQUARES = ROWS * COLS          // 90
    const val NUM_INPUT_CHANNELS = 15
    const val NUM_ACTIONS = 2086

    // Piece type channel offsets (must match Python PIECE_* constants)
    private const val CH_GENERAL = 0
    private const val CH_ADVISOR = 1
    private const val CH_ELEPHANT = 2
    private const val CH_HORSE = 3
    private const val CH_CHARIOT = 4
    private const val CH_CANNON = 5
    private const val CH_SOLDIER = 6
    private const val NUM_PIECE_TYPES = 7

    /**
     * Pre-computed move tables. Mirrors Python's _generate_all_possible_moves()
     * and produces the identical sorted list of 2086 (from_pos, to_pos) pairs.
     */
    val allMoves: List<Pair<Int, Int>>
    private val moveToIdx: Map<Pair<Int, Int>, Int>

    init {
        val movesSet = mutableSetOf<Pair<Int, Int>>()

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val fromPos = r * COLS + c

                // Cardinal moves (Chariot, Cannon, General, Soldier)
                for ((dr, dc) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                    for (step in 1 until maxOf(ROWS, COLS)) {
                        val nr = r + dr * step
                        val nc = c + dc * step
                        if (nr in 0 until ROWS && nc in 0 until COLS) {
                            movesSet.add(fromPos to (nr * COLS + nc))
                        }
                    }
                }

                // Horse / Knight L-shaped jumps
                for ((dr, dc) in listOf(
                    -2 to -1, -2 to 1, -1 to -2, -1 to 2,
                    1 to -2, 1 to 2, 2 to -1, 2 to 1
                )) {
                    val nr = r + dr
                    val nc = c + dc
                    if (nr in 0 until ROWS && nc in 0 until COLS) {
                        movesSet.add(fromPos to (nr * COLS + nc))
                    }
                }
            }
        }

        // Advisor moves: 1-step diagonal within palace
        val advisorSquares = listOf(
            0 to 3, 0 to 5, 1 to 4, 2 to 3, 2 to 5,   // Black palace
            7 to 3, 7 to 5, 8 to 4, 9 to 3, 9 to 5    // Red palace
        )
        for ((r, c) in advisorSquares) {
            val fromPos = r * COLS + c
            for ((dr, dc) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until ROWS && nc in 0 until COLS) {
                    val inPalace = (nr in 0..2 && nc in 3..5) || (nr in 7..9 && nc in 3..5)
                    if (inPalace) {
                        movesSet.add(fromPos to (nr * COLS + nc))
                    }
                }
            }
        }

        // Elephant moves: 2-step diagonal, same half of board
        val elephantSquares = listOf(
            0 to 2, 0 to 6, 2 to 0, 2 to 4, 2 to 8, 4 to 2, 4 to 6,   // Black
            5 to 2, 5 to 6, 7 to 0, 7 to 4, 7 to 8, 9 to 2, 9 to 6    // Red
        )
        for ((r, c) in elephantSquares) {
            val fromPos = r * COLS + c
            for ((dr, dc) in listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2)) {
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until ROWS && nc in 0 until COLS) {
                    val sameHalf = (r <= 4 && nr <= 4) || (r >= 5 && nr >= 5)
                    if (sameHalf) {
                        movesSet.add(fromPos to (nr * COLS + nc))
                    }
                }
            }
        }

        // Sort for deterministic indexing (same as Python sorted())
        allMoves = movesSet.sortedWith(compareBy({ it.first }, { it.second }))
        moveToIdx = HashMap<Pair<Int, Int>, Int>(allMoves.size * 2).also { map ->
            allMoves.forEachIndexed { idx, move -> map[move] = idx }
        }

        check(allMoves.size == NUM_ACTIONS) {
            "Expected $NUM_ACTIONS moves but generated ${allMoves.size}"
        }
    }

    // ---- PieceType → channel offset mapping ----

    private fun pieceTypeToChannel(type: PieceType): Int = when (type) {
        PieceType.GENERAL  -> CH_GENERAL
        PieceType.ADVISOR  -> CH_ADVISOR
        PieceType.ELEPHANT -> CH_ELEPHANT
        PieceType.HORSE    -> CH_HORSE
        PieceType.CHARIOT  -> CH_CHARIOT
        PieceType.CANNON   -> CH_CANNON
        PieceType.SOLDIER  -> CH_SOLDIER
    }

    // ---- Public API ----

    /**
     * Convert a [Board] to a flat FloatArray matching the Python (15, 10, 9) layout.
     *
     * Planes 0-6:  Red pieces (one-hot per piece type)
     * Planes 7-13: Black pieces
     * Plane 14:    Current player (all 1s = Red to move, all 0s = Black)
     *
     * The array is in CHW order: index = channel * (ROWS * COLS) + row * COLS + col.
     */
    fun boardToTensor(board: Board): FloatArray {
        val tensor = FloatArray(NUM_INPUT_CHANNELS * ROWS * COLS)

        for (piece in board.getAllPieces()) {
            val colorOffset = if (piece.color == PieceColor.RED) 0 else NUM_PIECE_TYPES
            val channel = colorOffset + pieceTypeToChannel(piece.type)
            val idx = channel * NUM_SQUARES + piece.position.row * COLS + piece.position.col
            tensor[idx] = 1.0f
        }

        // Current player plane (channel 14)
        if (board.currentPlayer == PieceColor.RED) {
            val base = 14 * NUM_SQUARES
            for (i in 0 until NUM_SQUARES) {
                tensor[base + i] = 1.0f
            }
        }

        return tensor
    }

    /**
     * Map a move (from → to positions) to the policy index [0, 2086).
     * Returns -1 if the move is not in the action space.
     */
    fun moveToIndex(from: Position, to: Position): Int {
        val fromPos = from.row * COLS + from.col
        val toPos = to.row * COLS + to.col
        return moveToIdx[fromPos to toPos] ?: -1
    }

    /**
     * Map a policy index back to (from, to) positions.
     */
    fun indexToMove(index: Int): Pair<Position, Position> {
        val (fromPos, toPos) = allMoves[index]
        return Position(fromPos / COLS, fromPos % COLS) to Position(toPos / COLS, toPos % COLS)
    }
}
