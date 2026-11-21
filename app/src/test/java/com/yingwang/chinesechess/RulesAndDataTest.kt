package com.yingwang.chinesechess

import com.yingwang.chinesechess.ai.PikafishEngine
import com.yingwang.chinesechess.ai.Review
import com.yingwang.chinesechess.ai.WeakPlay
import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Fen
import com.yingwang.chinesechess.model.Move
import com.yingwang.chinesechess.model.Position
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class RulesAndDataTest {

    private fun uciMove(board: Board, uci: String): Move {
        val from = Position(9 - (uci[1] - '0'), uci[0] - 'a')
        val to = Position(9 - (uci[3] - '0'), uci[2] - 'a')
        val piece = board.getPiece(from) ?: error("No piece on $from for $uci")
        return Move(from, to, piece, board.getPiece(to))
    }

    @Test
    fun fenRoundTripsTheOpening() {
        val start = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
        assertEquals(start, Fen.format(Board.createInitialBoard()))
        assertEquals(start, Fen.format(Fen.parse("$start - - 0 1")))
    }

    @Test
    fun bothNotationsReadTheCommonestOpening() {
        val board = Board.createInitialBoard()
        val cannon = uciMove(board, "h2e2")
        assertEquals("炮二平五", MoveNotation.format(cannon, board))
        assertEquals("C2=5", MoveNotation.formatWestern(cannon, board))
        val after = board.copy().also { it.makeMoveInPlace(cannon) }
        val horse = uciMove(after, "h9g7")
        assertEquals("H8+7", MoveNotation.formatWestern(horse, after))
        assertEquals("馬８进７", MoveNotation.format(horse, after))
    }

    @Test
    fun weakPlayNeverMissesAMateOrWalksIntoOne() {
        val mate = PikafishEngine.Score(cp = null, mate = 3)
        val mated = PikafishEngine.Score(cp = null, mate = -2)
        val quiet = PikafishEngine.Score(cp = 40, mate = null)
        repeat(200) {
            assertEquals("mate", WeakPlay.pick(listOf("quiet" to quiet, "mate" to mate), 100, Random(it)))
            assertEquals("quiet", WeakPlay.pick(listOf("mated" to mated, "quiet" to quiet), 100, Random(it)))
        }
    }

    @Test
    fun weakPlayPrefersBetterMovesButNotAlways() {
        val options = listOf("best" to PikafishEngine.Score(50, null), "worse" to PikafishEngine.Score(-50, null))
        val rng = Random(1)
        val picks = (1..2000).map { WeakPlay.pick(options, 60, rng) }
        val best = picks.count { it == "best" }
        assertTrue("best picked $best times", best in 1400..1800)
    }

    /**
     * Every study replays its engine solution through the app's own move generator: the
     * position parses, it is red to move and not already over, each move is legal, and the
     * line ends in mate within the advertised number of red moves.
     */
    @Test
    fun everyEndgameSolutionMatesUnderTheAppsRules() {
        val file = listOf(File("src/main/assets/endgames.json"), File("app/src/main/assets/endgames.json")).first { it.exists() }
        val studies = JSONArray(file.readText())
        assertTrue("no studies", studies.length() > 0)
        val ids = mutableSetOf<String>()
        for (i in 0 until studies.length()) {
            val study = studies.getJSONObject(i)
            val id = study.getString("id")
            assertTrue("duplicate id $id", ids.add(id))
            val board = Fen.parse(study.getString("fen"))
            assertFalse("$id starts in check for the side not to move", board.isInCheck(board.currentPlayer.opposite()))
            assertFalse("$id is already over", board.isCheckmate() || board.isStalemate())
            val solution = study.getJSONArray("solution")
            var redMoves = 0
            var walk = board
            for (k in 0 until solution.length()) {
                val move = uciMove(walk, solution.getString(k))
                assertTrue("$id: ${solution.getString(k)} is not legal", walk.getAllLegalMoves().contains(move))
                if (move.piece.color == board.currentPlayer) redMoves++
                walk = walk.copy().also { it.makeMoveInPlace(move) }
            }
            assertTrue("$id: solution does not end in mate", walk.isCheckmate())
            assertEquals("$id: mate length", study.getInt("mate_in"), redMoves)
            assertNotNull(study.optString("source"))
        }
    }

    @Test
    fun theReviewBlamesTheMoveThatLostTheGameNotTheLastOne() {
        // From level to two pawns down: a real mistake.
        val spoiled = Review.cost(PikafishEngine.Score(cp = 0, mate = null), PikafishEngine.Score(cp = 200, mate = null))
        // Eleven pawns down and then mated in one: the game was already gone.
        val alreadyLost = Review.cost(PikafishEngine.Score(cp = -1140, mate = null), PikafishEngine.Score(cp = null, mate = 1))
        assertTrue("spoiled $spoiled", spoiled > 0.15)
        assertTrue("already lost $alreadyLost", alreadyLost < 0.02)
        assertTrue(spoiled > alreadyLost)
    }

    @Test
    fun theNetworkIsUnpackedWhenMissingOrNotTheAppsAndOtherwiseLeftAlone() {
        val shipped = listOf(File("src/main/assets/pikafish.nnue"), File("app/src/main/assets/pikafish.nnue"))
            .first { it.exists() }.length()
        // First use.
        assertTrue(PikafishEngine.needsUnpacking(onDiskLength = null, appLength = shipped))
        // A complete copy from any earlier launch stays: no unpacking again on every start.
        assertFalse(PikafishEngine.needsUnpacking(onDiskLength = shipped, appLength = shipped))
        // An unpacking cut short (the storage full, the app ended), or a release with another network.
        assertTrue(PikafishEngine.needsUnpacking(onDiskLength = shipped / 2, appLength = shipped))
        assertTrue(PikafishEngine.needsUnpacking(onDiskLength = 0, appLength = shipped))
        assertTrue(PikafishEngine.needsUnpacking(onDiskLength = shipped + 1024, appLength = shipped))
        // When the app's size cannot be told, a copy that is there is used, as before.
        assertFalse(PikafishEngine.needsUnpacking(onDiskLength = shipped, appLength = -1))
        assertTrue(PikafishEngine.needsUnpacking(onDiskLength = null, appLength = -1))
    }
}
