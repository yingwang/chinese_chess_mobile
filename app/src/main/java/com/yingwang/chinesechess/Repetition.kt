package com.yingwang.chinesechess

import com.yingwang.chinesechess.GameController.GameResult
import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Move
import com.yingwang.chinesechess.model.Piece
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.PieceType
import com.yingwang.chinesechess.model.Position

/**
 * Adjudication of a repeated position: 长将 (perpetual check), 长捉 (perpetual chase) or a plain
 * repetition. Pure functions over the model; the web version (js/repetition.js) judges the same
 * way, and both are checked against the shared cases in src/test/resources/repetition-cases.json.
 *
 * When a position (board and side to move) occurs for the third time, the moves of the cycle
 * between its previous occurrence and this one are classified, every one of them, for the
 * side that made it:
 *
 *   - a CHECK move gives check;
 *   - a CHASE move creates a new attack on an opponent piece P: after the move some piece Q of
 *     the mover (never the general or a soldier) could legally capture P, Q did not reach P
 *     before the move (by the way it moves, whether or not taking was legal then: a piece freed
 *     from a check or a pin starts no chase; for the moved piece, "before" means from its old
 *     square, and an attack uncovered by the move counts the same as one made by the piece that
 *     moved), P is not a general nor a soldier still on its own side of the river, and either P
 *     is unprotected (no opponent piece could legally recapture on its square) or Q is worth
 *     less than P (chariot above horse and cannon, which rank alike, above the rest). Attacking
 *     a protected piece of equal or lower value is an offer to exchange, not a chase.
 *
 * A side's conduct over the cycle: CHECK if every move checks; CHECK_AND_CHASE if every move
 * checks or chases with at least one of each; CHASE if every move chases (the same piece or
 * different ones); NONE otherwise. Check and check-and-chase are the more serious conduct,
 * chase the less; the side whose conduct is more serious loses ([GameResult.PerpetualCheck] or
 * [GameResult.PerpetualChase] with the other side the winner); when both are equally serious,
 * including both doing nothing of the kind, it is [GameResult.RepetitionDraw].
 */
object Repetition {
    enum class Conduct { NONE, CHASE, CHECK_AND_CHASE, CHECK }

    /** How one move classifies; it can be both a check and a chase. */
    data class MoveKind(val check: Boolean, val chase: Boolean)

    data class Verdict(val result: GameResult, val red: Conduct, val black: Conduct)

    /** Worth for the chase rule: chariot above horse and cannon, which rank alike, above the rest. */
    private fun chaseRank(type: PieceType): Int = when (type) {
        PieceType.CHARIOT -> 3
        PieceType.HORSE, PieceType.CANNON -> 2
        else -> 1
    }

    private fun hasCrossedRiver(piece: Piece): Boolean =
        if (piece.color == PieceColor.RED) piece.position.row <= 4 else piece.position.row >= 5

    private fun Board.withSideToMove(color: PieceColor): Board = copy().also { it.currentPlayer = color }

    /** Whether [color] may play [move] on [board]: it leaves no own general in check or facing the other. */
    private fun isLegalFor(board: Board, move: Move, color: PieceColor): Boolean =
        !board.makeMove(move).isInCheck(color)

    /** The squares of the opponent pieces [attacker] reaches on [board], legal to take or not. */
    private fun reachedSquares(board: Board, attacker: Piece): Set<Position> =
        attacker.getLegalMoves(board).filter { it.capturedPiece != null }.map { it.to }.toSet()

    /** Whether, once an attacker has taken the piece on [target] (giving [after]), [defender] can take it back. */
    private fun isProtected(after: Board, target: Position, defender: PieceColor): Boolean {
        val board = after.withSideToMove(defender)
        return board.getPiecesByColor(defender).any { piece ->
            piece.getLegalMoves(board).any { it.to == target && isLegalFor(board, it, defender) }
        }
    }

    private fun boardAfter(before: Board, move: Move): Board = before.copy().also { it.makeMoveInPlace(move) }

    /** How [move], played on [before] (its mover to move there), classifies. */
    fun classifyMove(before: Board, move: Move): MoveKind {
        val mover = move.piece.color
        val opponent = mover.opposite()
        val after = boardAfter(before, move)
        val check = after.isInCheck(opponent)

        // What the mover's pieces reached before the move, by the square each piece stood on.
        val reachedBefore = before.getPiecesByColor(mover)
            .filter { it.type != PieceType.GENERAL && it.type != PieceType.SOLDIER }
            .associate { it.position to reachedSquares(before, it) }

        val post = after.withSideToMove(mover)
        val chase = post.getPiecesByColor(mover).any { attacker ->
            if (attacker.type == PieceType.GENERAL || attacker.type == PieceType.SOLDIER) return@any false
            val oldSquare = if (attacker.position == move.to) move.from else attacker.position
            val before_ = reachedBefore[oldSquare] ?: emptySet()
            attacker.getLegalMoves(post).any { capture ->
                val target = capture.capturedPiece ?: return@any false
                if (capture.to in before_) return@any false
                if (target.type == PieceType.GENERAL) return@any false
                if (target.type == PieceType.SOLDIER && !hasCrossedRiver(target)) return@any false
                if (!isLegalFor(post, capture, mover)) return@any false
                chaseRank(attacker.type) < chaseRank(target.type) ||
                    !isProtected(post.makeMove(capture), capture.to, opponent)
            }
        }
        return MoveKind(check, chase)
    }

    /** A side's conduct over its moves of the cycle, from their classifications. */
    fun conductOf(kinds: List<MoveKind>): Conduct = when {
        kinds.isEmpty() -> Conduct.NONE
        kinds.all { it.check } -> Conduct.CHECK
        kinds.all { it.check || it.chase } -> if (kinds.any { it.check }) Conduct.CHECK_AND_CHASE else Conduct.CHASE
        else -> Conduct.NONE
    }

    private fun severity(conduct: Conduct): Int = when (conduct) {
        Conduct.CHECK, Conduct.CHECK_AND_CHASE -> 2
        Conduct.CHASE -> 1
        Conduct.NONE -> 0
    }

    /** The result of a third occurrence given each side's conduct over the cycle. */
    fun outcome(red: Conduct, black: Conduct): GameResult {
        val r = severity(red)
        val b = severity(black)
        if (r == b) return GameResult.RepetitionDraw
        val winner = if (r > b) PieceColor.BLACK else PieceColor.RED
        return if (maxOf(r, b) == 2) GameResult.PerpetualCheck(winner) else GameResult.PerpetualChase(winner)
    }

    /**
     * Judges the cycle [moves]`[from until to]`: the moves from the previous occurrence of the
     * repeated position to its latest one, [start] being the board before move 0.
     */
    fun judgeCycle(start: Board, moves: List<Move>, from: Int, to: Int): Verdict {
        var board = start
        for (i in 0 until from) board = boardAfter(board, moves[i])
        val red = mutableListOf<MoveKind>()
        val black = mutableListOf<MoveKind>()
        for (i in from until to) {
            val move = moves[i]
            (if (move.piece.color == PieceColor.RED) red else black).add(classifyMove(board, move))
            board = boardAfter(board, move)
        }
        val redConduct = conductOf(red)
        val blackConduct = conductOf(black)
        return Verdict(outcome(redConduct, blackConduct), redConduct, blackConduct)
    }

    /**
     * The verdict if the last of [positionHashes] (the position after the last of [moves],
     * played from [start]) is a third occurrence; null if it is not. The hashes are one per
     * position, the first for [start].
     */
    fun judge(start: Board, moves: List<Move>, positionHashes: List<Long>): Verdict? {
        val last = positionHashes.size - 1
        if (last < 0) return null
        val hash = positionHashes[last]
        var seen = 0
        var previous = -1
        for (i in 0 until last) {
            if (positionHashes[i] == hash) {
                seen++
                previous = i
            }
        }
        if (seen < 2) return null
        return judgeCycle(start, moves, previous, last)
    }
}
