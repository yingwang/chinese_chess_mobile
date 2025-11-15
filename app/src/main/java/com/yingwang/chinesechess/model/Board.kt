package com.yingwang.chinesechess.model

/**
 * Represents the Chinese chess board state
 * 10 rows x 9 columns
 */
class Board {
    private val pieces = mutableMapOf<Position, Piece>()
    var currentPlayer = PieceColor.RED
        private set

    companion object {
        const val ROWS = 10
        const val COLS = 9

        /**
         * Creates a new board with standard starting position
         */
        fun createInitialBoard(): Board {
            val board = Board()

            // Black pieces (top)
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.BLACK, Position(0, 0)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.BLACK, Position(0, 1)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 2)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 3)))
            board.addPiece(Piece(PieceType.GENERAL, PieceColor.BLACK, Position(0, 4)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.BLACK, Position(0, 5)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.BLACK, Position(0, 6)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.BLACK, Position(0, 7)))
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.BLACK, Position(0, 8)))

            board.addPiece(Piece(PieceType.CANNON, PieceColor.BLACK, Position(2, 1)))
            board.addPiece(Piece(PieceType.CANNON, PieceColor.BLACK, Position(2, 7)))

            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 0)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 2)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 4)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 6)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.BLACK, Position(3, 8)))

            // Red pieces (bottom)
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.RED, Position(9, 0)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.RED, Position(9, 1)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.RED, Position(9, 2)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.RED, Position(9, 3)))
            board.addPiece(Piece(PieceType.GENERAL, PieceColor.RED, Position(9, 4)))
            board.addPiece(Piece(PieceType.ADVISOR, PieceColor.RED, Position(9, 5)))
            board.addPiece(Piece(PieceType.ELEPHANT, PieceColor.RED, Position(9, 6)))
            board.addPiece(Piece(PieceType.HORSE, PieceColor.RED, Position(9, 7)))
            board.addPiece(Piece(PieceType.CHARIOT, PieceColor.RED, Position(9, 8)))

            board.addPiece(Piece(PieceType.CANNON, PieceColor.RED, Position(7, 1)))
            board.addPiece(Piece(PieceType.CANNON, PieceColor.RED, Position(7, 7)))

            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 0)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 2)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 4)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 6)))
            board.addPiece(Piece(PieceType.SOLDIER, PieceColor.RED, Position(6, 8)))

            return board
        }
    }

    private fun addPiece(piece: Piece) {
        pieces[piece.position] = piece
    }

    fun getPiece(position: Position): Piece? = pieces[position]

    fun getAllPieces(): List<Piece> = pieces.values.toList()

    fun getPiecesByColor(color: PieceColor): List<Piece> =
        pieces.values.filter { it.color == color }

    /**
     * Get all legal moves for the current player
     */
    fun getAllLegalMoves(): List<Move> {
        val moves = mutableListOf<Move>()
        for (piece in getPiecesByColor(currentPlayer)) {
            moves.addAll(piece.getLegalMoves(this))
        }
        // Filter out moves that would put own general in check
        return moves.filterNot { move ->
            val testBoard = makeMove(move)
            testBoard.isInCheck(currentPlayer)
        }
    }

    /**
     * Check if generals are facing each other (flying general rule)
     */
    fun isGeneralsFacing(): Boolean {
        val redGeneral = pieces.values.find { it.type == PieceType.GENERAL && it.color == PieceColor.RED }
        val blackGeneral = pieces.values.find { it.type == PieceType.GENERAL && it.color == PieceColor.BLACK }

        if (redGeneral == null || blackGeneral == null) return false
        if (redGeneral.position.col != blackGeneral.position.col) return false

        // Check if there are any pieces between generals
        val minRow = minOf(redGeneral.position.row, blackGeneral.position.row)
        val maxRow = maxOf(redGeneral.position.row, blackGeneral.position.row)

        for (row in (minRow + 1) until maxRow) {
            if (getPiece(Position(row, redGeneral.position.col)) != null) {
                return false
            }
        }

        return true
    }

    /**
     * Check if the specified color is in check
     */
    fun isInCheck(color: PieceColor): Boolean {
        val general = pieces.values.find { it.type == PieceType.GENERAL && it.color == color }
            ?: return true // General captured = checkmate

        // Check if any opponent piece can capture the general
        val opponentMoves = getPiecesByColor(color.opposite()).flatMap { piece ->
            piece.getLegalMoves(this)
        }

        return opponentMoves.any { it.to == general.position }
    }

    /**
     * Check if the current player is in checkmate
     */
    fun isCheckmate(): Boolean {
        return isInCheck(currentPlayer) && getAllLegalMoves().isEmpty()
    }

    /**
     * Check if the game is a stalemate
     */
    fun isStalemate(): Boolean {
        return !isInCheck(currentPlayer) && getAllLegalMoves().isEmpty()
    }

    /**
     * Make a move and return a new board with the move applied
     */
    fun makeMove(move: Move): Board {
        val newBoard = Board()
        newBoard.pieces.putAll(this.pieces)
        newBoard.currentPlayer = this.currentPlayer

        // Remove piece from old position
        newBoard.pieces.remove(move.from)

        // Remove captured piece if any
        if (move.capturedPiece != null) {
            newBoard.pieces.remove(move.to)
        }

        // Add piece to new position
        newBoard.pieces[move.to] = move.piece.copy(newPosition = move.to)

        return newBoard
    }

    /**
     * Make a move in place (mutates the board)
     */
    fun makeMoveInPlace(move: Move) {
        pieces.remove(move.from)
        if (move.capturedPiece != null) {
            pieces.remove(move.to)
        }
        pieces[move.to] = move.piece.copy(newPosition = move.to)
        currentPlayer = currentPlayer.opposite()
    }

    /**
     * Copy the board
     */
    fun copy(): Board {
        val newBoard = Board()
        newBoard.pieces.putAll(this.pieces)
        newBoard.currentPlayer = this.currentPlayer
        return newBoard
    }

    /**
     * Get a hash code for this board position (for transposition table)
     */
    fun getPositionHash(): Long {
        var hash = 0L
        for ((pos, piece) in pieces) {
            hash = hash xor (piece.type.ordinal.toLong() shl (pos.row * 9 + pos.col))
            hash = hash xor (piece.color.ordinal.toLong() shl (pos.row * 9 + pos.col + 32))
        }
        hash = hash xor (currentPlayer.ordinal.toLong() shl 63)
        return hash
    }
}
