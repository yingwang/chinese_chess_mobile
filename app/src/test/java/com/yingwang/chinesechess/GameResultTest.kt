package com.yingwang.chinesechess

import com.yingwang.chinesechess.GameController.GameResult
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.ai.Evaluator
import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Fen
import com.yingwang.chinesechess.model.Move
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.Position
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a game ends (GameController.resultOf), above all 困毙: no legal move loses, in check or not. */
class GameResultTest {

    private fun play(board: Board, from: Position, to: Position): Board {
        val move = board.getAllLegalMoves().single { it.from == from && it.to == to }
        return board.copy().also { it.makeMoveInPlace(move) }
    }

    private fun resultAfter(vararg boards: Board): GameResult? =
        GameController.resultOf(boards.last(), boards.map { it.getPositionHash() })

    /**
     * Black's general alone on d10, red's on e1 and a red pawn on b9. Red plays the pawn across
     * to c9: it does not attack d10, so black is not in check, but the general cannot step to
     * d9 (the pawn takes it) nor to e10 (it would face red's general down the open file).
     */
    private val beforeStalemate = Fen.parse("3k5/1P7/9/9/9/9/9/9/9/4K4 w")
    private val stalemate = play(beforeStalemate, Position(1, 1), Position(1, 2))

    @Test
    fun theStalematedPositionIsWhatItSeems() {
        assertEquals("3k5/2P6/9/9/9/9/9/9/9/4K4 b", Fen.format(stalemate))
        assertEquals(PieceColor.BLACK, stalemate.currentPlayer)
        assertFalse("black is not in check", stalemate.isInCheck(PieceColor.BLACK))
        assertTrue("black has no legal move", stalemate.getAllLegalMoves().isEmpty())
        assertTrue(stalemate.isStalemate())
        assertFalse(stalemate.isCheckmate())
        // Before red's move black was not stuck yet: d9 was free.
        assertFalse(beforeStalemate.copy().also { it.currentPlayer = PieceColor.BLACK }.getAllLegalMoves().isEmpty())
    }

    @Test
    fun stalemateLosesForTheSideThatCannotMove() {
        val result = resultAfter(beforeStalemate, stalemate)
        assertEquals(GameResult.Stalemate(PieceColor.RED), result)
        assertEquals(PieceColor.RED, result!!.winner)
        // The same position with red the side stuck: black wins.
        val mirrored = Fen.parse("4k4/9/9/9/9/9/9/9/2p6/3K5 w")
        assertTrue(mirrored.getAllLegalMoves().isEmpty())
        assertFalse(mirrored.isInCheck(PieceColor.RED))
        assertEquals(GameResult.Stalemate(PieceColor.BLACK), GameController.resultOf(mirrored, listOf(mirrored.getPositionHash())))
    }

    @Test
    fun mateRepetitionAndPerpetualCheckAreAsBefore() {
        assertNull(resultAfter(beforeStalemate))
        assertNull(resultAfter(Board.createInitialBoard()))
        // Mate: the same corner, but a chariot comes to a10 and gives check along the back rank.
        val mate = Fen.parse("3k5/2P6/9/9/9/9/9/9/9/R3K4 w").let { play(it, Position(9, 0), Position(0, 0)) }
        assertEquals(GameResult.Checkmate(PieceColor.RED), resultAfter(mate))
        assertEquals(PieceColor.RED, GameController.GameResult.Checkmate(PieceColor.RED).winner)
        // A third occurrence: a draw, or a loss for the side giving check every time.
        val quiet = Board.createInitialBoard()
        assertEquals(GameResult.RepetitionDraw, GameController.resultOf(quiet, List(3) { quiet.getPositionHash() }))
        assertNull(GameResult.RepetitionDraw.winner)
        // Black in check from the chariot on the d-file, with e9 still free.
        val checked = Fen.parse("9/3k5/9/9/9/9/9/9/9/3R1K3 b")
        assertTrue(checked.isInCheck(PieceColor.BLACK))
        assertFalse(checked.getAllLegalMoves().isEmpty())
        assertEquals(GameResult.PerpetualCheck(PieceColor.BLACK), GameController.resultOf(checked, List(3) { checked.getPositionHash() }))
    }

    @Test
    fun theFallbackEngineScoresStalemateAsALossAndPlaysIntoIt() {
        // Black to move and stuck: as good as mated, for red.
        assertTrue(Evaluator.evaluate(stalemate) >= 90000)
        // Red's only winning move is the one that leaves black without a move.
        // Every legal move allowed: that keeps the opening book out of it.
        val move: Move? = runBlocking {
            ChessAI(maxDepth = 2, timeLimit = 5000, quiescenceDepth = 0)
                .findBestMove(beforeStalemate, allowedMoves = beforeStalemate.getAllLegalMoves())
        }
        assertEquals(Position(1, 1), move?.from)
        assertEquals(Position(1, 2), move?.to)
    }
}
