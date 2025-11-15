package com.yingwang.chinesechess.model

/**
 * Represents a Chinese chess piece
 */
data class Piece(
    val type: PieceType,
    val color: PieceColor,
    val position: Position
) {
    /**
     * Generate all legal moves for this piece on the given board
     */
    fun getLegalMoves(board: Board): List<Move> {
        val moves = mutableListOf<Move>()

        when (type) {
            PieceType.GENERAL -> generateGeneralMoves(board, moves)
            PieceType.ADVISOR -> generateAdvisorMoves(board, moves)
            PieceType.ELEPHANT -> generateElephantMoves(board, moves)
            PieceType.HORSE -> generateHorseMoves(board, moves)
            PieceType.CHARIOT -> generateChariotMoves(board, moves)
            PieceType.CANNON -> generateCannonMoves(board, moves)
            PieceType.SOLDIER -> generateSoldierMoves(board, moves)
        }

        return moves
    }

    private fun generateGeneralMoves(board: Board, moves: MutableList<Move>) {
        // General moves within palace (3x3 area)
        val palaceRows = if (color == PieceColor.RED) 7..9 else 0..2
        val palaceCols = 3..5

        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            val newPos = Position(position.row + dir.row, position.col + dir.col)
            if (newPos.row in palaceRows && newPos.col in palaceCols) {
                val target = board.getPiece(newPos)
                if (target == null || target.color != color) {
                    moves.add(Move(position, newPos, this, target))
                }
            }
        }

        // Flying general rule - generals cannot face each other
        // Check if moving general would face opponent's general
        moves.removeIf { move ->
            val testBoard = board.makeMove(move)
            testBoard.isGeneralsFacing()
        }
    }

    private fun generateAdvisorMoves(board: Board, moves: MutableList<Move>) {
        // Advisor moves diagonally within palace
        val palaceRows = if (color == PieceColor.RED) 7..9 else 0..2
        val palaceCols = 3..5

        val diagonals = listOf(
            Position(-1, -1), Position(-1, 1),
            Position(1, -1), Position(1, 1)
        )

        for (diag in diagonals) {
            val newPos = Position(position.row + diag.row, position.col + diag.col)
            if (newPos.row in palaceRows && newPos.col in palaceCols) {
                val target = board.getPiece(newPos)
                if (target == null || target.color != color) {
                    moves.add(Move(position, newPos, this, target))
                }
            }
        }
    }

    private fun generateElephantMoves(board: Board, moves: MutableList<Move>) {
        // Elephant moves diagonally 2 steps, cannot cross river
        val allowedRows = if (color == PieceColor.RED) 5..9 else 0..4

        val elephantMoves = listOf(
            Pair(Position(-2, -2), Position(-1, -1)),
            Pair(Position(-2, 2), Position(-1, 1)),
            Pair(Position(2, -2), Position(1, -1)),
            Pair(Position(2, 2), Position(1, 1))
        )

        for ((dest, block) in elephantMoves) {
            val newPos = Position(position.row + dest.row, position.col + dest.col)
            val blockPos = Position(position.row + block.row, position.col + block.col)

            if (newPos.isValid() && newPos.row in allowedRows) {
                // Check if elephant eye is blocked
                if (board.getPiece(blockPos) == null) {
                    val target = board.getPiece(newPos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                }
            }
        }
    }

    private fun generateHorseMoves(board: Board, moves: MutableList<Move>) {
        // Horse moves in L-shape, can be blocked by adjacent pieces
        val horseMoves = listOf(
            Pair(Position(-2, -1), Position(-1, 0)),
            Pair(Position(-2, 1), Position(-1, 0)),
            Pair(Position(-1, -2), Position(0, -1)),
            Pair(Position(-1, 2), Position(0, 1)),
            Pair(Position(1, -2), Position(0, -1)),
            Pair(Position(1, 2), Position(0, 1)),
            Pair(Position(2, -1), Position(1, 0)),
            Pair(Position(2, 1), Position(1, 0))
        )

        for ((dest, block) in horseMoves) {
            val newPos = Position(position.row + dest.row, position.col + dest.col)
            val blockPos = Position(position.row + block.row, position.col + block.col)

            if (newPos.isValid()) {
                // Check if horse leg is blocked
                if (board.getPiece(blockPos) == null) {
                    val target = board.getPiece(newPos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                }
            }
        }
    }

    private fun generateChariotMoves(board: Board, moves: MutableList<Move>) {
        // Chariot moves in straight lines (horizontal and vertical)
        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            var steps = 1
            while (true) {
                val newPos = Position(
                    position.row + dir.row * steps,
                    position.col + dir.col * steps
                )

                if (!newPos.isValid()) break

                val target = board.getPiece(newPos)
                if (target == null) {
                    moves.add(Move(position, newPos, this, null))
                    steps++
                } else {
                    if (target.color != color) {
                        moves.add(Move(position, newPos, this, target))
                    }
                    break
                }
            }
        }
    }

    private fun generateCannonMoves(board: Board, moves: MutableList<Move>) {
        // Cannon moves like chariot but captures by jumping over one piece
        val directions = listOf(
            Position(-1, 0), Position(1, 0),
            Position(0, -1), Position(0, 1)
        )

        for (dir in directions) {
            var steps = 1
            var jumped = false

            while (true) {
                val newPos = Position(
                    position.row + dir.row * steps,
                    position.col + dir.col * steps
                )

                if (!newPos.isValid()) break

                val target = board.getPiece(newPos)

                if (!jumped) {
                    // Before jumping, can only move to empty squares
                    if (target == null) {
                        moves.add(Move(position, newPos, this, null))
                    } else {
                        jumped = true
                    }
                } else {
                    // After jumping, can only capture
                    if (target != null) {
                        if (target.color != color) {
                            moves.add(Move(position, newPos, this, target))
                        }
                        break
                    }
                }

                steps++
            }
        }
    }

    private fun generateSoldierMoves(board: Board, moves: MutableList<Move>) {
        // Soldier moves forward, and sideways after crossing river
        val forwardDir = if (color == PieceColor.RED) -1 else 1
        val hasCrossedRiver = if (color == PieceColor.RED) position.row <= 4 else position.row >= 5

        // Forward move
        val forwardPos = Position(position.row + forwardDir, position.col)
        if (forwardPos.isValid()) {
            val target = board.getPiece(forwardPos)
            if (target == null || target.color != color) {
                moves.add(Move(position, forwardPos, this, target))
            }
        }

        // Sideways moves (only after crossing river)
        if (hasCrossedRiver) {
            for (colDir in listOf(-1, 1)) {
                val sidePos = Position(position.row, position.col + colDir)
                if (sidePos.isValid()) {
                    val target = board.getPiece(sidePos)
                    if (target == null || target.color != color) {
                        moves.add(Move(position, sidePos, this, target))
                    }
                }
            }
        }
    }

    fun copy(newPosition: Position = position): Piece {
        return Piece(type, color, newPosition)
    }
}
