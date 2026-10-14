package com.yingwang.chinesechess

import com.yingwang.chinesechess.GameController.GameResult
import com.yingwang.chinesechess.Repetition.Conduct
import com.yingwang.chinesechess.Repetition.MoveKind
import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Fen
import com.yingwang.chinesechess.model.Move
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.Position
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of repetition (长将, 长捉, a plain repetition) against the cases shared with the web
 * version: src/test/resources/repetition-cases.json is a copy of tests/repetition-cases.json in
 * the chinese_chess repository, which tests/repetition.test.mjs runs there. Both must pass it.
 */
class RepetitionTest {

    /** "a8a9" to the matching legal move on [board]; files a..i are columns, rank d is row 9 - d. */
    private fun parseMove(board: Board, text: String): Move {
        val from = Position(9 - (text[1] - '0'), text[0] - 'a')
        val to = Position(9 - (text[3] - '0'), text[2] - 'a')
        return board.getAllLegalMoves().firstOrNull { it.from == from && it.to == to }
            ?: error("$text is not legal on ${Fen.format(board)}")
    }

    private fun conduct(name: String): Conduct = when (name) {
        "none" -> Conduct.NONE
        "chase" -> Conduct.CHASE
        "checkAndChase" -> Conduct.CHECK_AND_CHASE
        "check" -> Conduct.CHECK
        else -> error("conduct $name")
    }

    private fun expected(type: String, winner: String?): GameResult = when (type) {
        "repetition" -> GameResult.RepetitionDraw
        "perpetualCheck" -> GameResult.PerpetualCheck(if (winner == "red") PieceColor.RED else PieceColor.BLACK)
        "perpetualChase" -> GameResult.PerpetualChase(if (winner == "red") PieceColor.RED else PieceColor.BLACK)
        else -> error("result $type")
    }

    @Test
    fun everySharedCaseIsJudgedAsTheWebVersionJudgesIt() {
        val text = javaClass.getResourceAsStream("/repetition-cases.json")!!.bufferedReader().readText()
        val cases = JSONObject(text).getJSONArray("cases")
        assertTrue(cases.length() >= 12)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val start = Fen.parse(case.getString("fen"))
            val texts = case.getJSONArray("moves")
            val moves = mutableListOf<Move>()
            val hashes = mutableListOf(start.getPositionHash())
            var board = start
            var verdict: Repetition.Verdict? = null
            for (k in 0 until texts.length()) {
                assertFalse("$id: over before ${texts.getString(k)}", board.getAllLegalMoves().isEmpty())
                val move = parseMove(board, texts.getString(k))
                moves.add(move)
                board = board.copy().also { it.makeMoveInPlace(move) }
                hashes.add(board.getPositionHash())
                verdict = Repetition.judge(start, moves, hashes)
                if (verdict != null) {
                    assertEquals("$id: a third occurrence before the last move", texts.length(), moves.size)
                    break
                }
            }
            val expect = case.getJSONObject("expect")
            val winner = expect.optString("winner").takeIf { it.isNotEmpty() && !expect.isNull("winner") }
            assertEquals("$id: result", expected(expect.getString("type"), winner), verdict?.result)
            val conduct = case.getJSONObject("conduct")
            assertEquals("$id: red's conduct", conduct(conduct.getString("red")), verdict?.red)
            assertEquals("$id: black's conduct", conduct(conduct.getString("black")), verdict?.black)
            // The controller reports the same through resultOf.
            assertEquals("$id: resultOf", verdict?.result, GameController.resultOf(board, hashes, start, moves))
        }
    }

    @Test
    fun theOutcomeTableCoversWhatNoShortGameReaches() {
        assertEquals(GameResult.RepetitionDraw, Repetition.outcome(Conduct.CHECK, Conduct.CHECK))
        assertEquals(GameResult.RepetitionDraw, Repetition.outcome(Conduct.CHECK, Conduct.CHECK_AND_CHASE))
        assertEquals(GameResult.PerpetualCheck(PieceColor.BLACK), Repetition.outcome(Conduct.CHECK, Conduct.CHASE))
        assertEquals(GameResult.PerpetualCheck(PieceColor.RED), Repetition.outcome(Conduct.CHASE, Conduct.CHECK_AND_CHASE))
        assertEquals(GameResult.PerpetualCheck(PieceColor.BLACK), Repetition.outcome(Conduct.CHECK_AND_CHASE, Conduct.NONE))
        assertEquals(GameResult.PerpetualChase(PieceColor.RED), Repetition.outcome(Conduct.NONE, Conduct.CHASE))
        assertEquals(GameResult.RepetitionDraw, Repetition.outcome(Conduct.CHASE, Conduct.CHASE))
        assertEquals(GameResult.RepetitionDraw, Repetition.outcome(Conduct.NONE, Conduct.NONE))

        val check = MoveKind(check = true, chase = false)
        val chase = MoveKind(check = false, chase = true)
        val both = MoveKind(check = true, chase = true)
        val quiet = MoveKind(check = false, chase = false)
        assertEquals(Conduct.CHECK, Repetition.conductOf(listOf(check, check)))
        assertEquals(Conduct.CHECK, Repetition.conductOf(listOf(both, both)))
        assertEquals(Conduct.CHECK_AND_CHASE, Repetition.conductOf(listOf(check, chase)))
        assertEquals(Conduct.CHECK_AND_CHASE, Repetition.conductOf(listOf(both, chase)))
        assertEquals(Conduct.CHASE, Repetition.conductOf(listOf(chase, chase)))
        assertEquals(Conduct.NONE, Repetition.conductOf(listOf(check, quiet)))
        assertEquals(Conduct.NONE, Repetition.conductOf(listOf(chase, quiet)))
        assertEquals(Conduct.NONE, Repetition.conductOf(emptyList()))
    }

    @Test
    fun nothingIsJudgedBeforeAThirdOccurrence() {
        val start = Board.createInitialBoard()
        assertNull(Repetition.judge(start, emptyList(), listOf(start.getPositionHash())))
        assertNull(Repetition.judge(start, emptyList(), emptyList()))
    }
}
