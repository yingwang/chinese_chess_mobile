package com.yingwang.chinesechess

import com.yingwang.chinesechess.model.Board
import com.yingwang.chinesechess.model.Fen
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.model.PieceType
import com.yingwang.chinesechess.model.Position
import com.yingwang.chinesechess.online.OnlineProtocol
import com.yingwang.chinesechess.online.OnlineProtocol.ResultType
import com.yingwang.chinesechess.online.OnlineProtocol.WireMove
import com.yingwang.chinesechess.online.OnlineProtocol.WireResult
import com.yingwang.chinesechess.ui.BoardOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The wire format shared with the web version (js/online.js, js/model.js): room codes, moves,
 * results and clocks, and the claim that a square means the same on both sides.
 */
class OnlineProtocolTest {

    // ── Room codes ──

    @Test
    fun codesAreSixCharactersFromTheUnambiguousAlphabet() {
        val random = Random(7)
        repeat(2000) {
            val code = OnlineProtocol.generateCode(random)
            assertEquals(6, code.length)
            assertTrue(code, code.all { it in OnlineProtocol.CODE_ALPHABET })
            assertTrue(OnlineProtocol.isValidCode(code))
        }
        // The alphabet is the web's, character for character.
        assertEquals("ABCDEFGHJKMNPQRSTUVWXYZ23456789", OnlineProtocol.CODE_ALPHABET)
        for (ambiguous in "IL01O") assertFalse(ambiguous in OnlineProtocol.CODE_ALPHABET)
    }

    @Test
    fun generatedCodesSpreadOverTheAlphabet() {
        val random = Random(11)
        val codes = (1..5000).map { OnlineProtocol.generateCode(random) }.toSet()
        assertEquals("5000 draws from 31^6 codes should not collide", 5000, codes.size)
        val used = codes.flatMap { it.toList() }.toSet()
        assertEquals(OnlineProtocol.CODE_ALPHABET.toSet(), used)
    }

    @Test
    fun typedCodesAreNormalisedThenValidated() {
        assertEquals("ABC234", OnlineProtocol.normalizeCode(" abc 234 "))
        assertEquals("ABC234", OnlineProtocol.normalizeCode("abc-234"))
        assertTrue(OnlineProtocol.isValidCode(OnlineProtocol.normalizeCode("xk7-p2q")))
        assertFalse(OnlineProtocol.isValidCode("ABCD"))          // the old four-character codes
        assertFalse(OnlineProtocol.isValidCode("ABC2345"))       // too long
        assertFalse(OnlineProtocol.isValidCode("ABCDE1"))        // 1 is not in the alphabet
        assertFalse(OnlineProtocol.isValidCode("ABCDEO"))        // nor O
        assertFalse(OnlineProtocol.isValidCode("abcdef"))        // lower case only after normalising
        assertFalse(OnlineProtocol.isValidCode(""))
    }

    @Test
    fun shareLinkCarriesTheCode() {
        assertEquals("https://yingwang.github.io/chinese_chess/?room=ABC234", OnlineProtocol.joinLink("ABC234"))
    }

    // ── Coordinates ──

    @Test
    fun startingPositionIsTheWebsSquareForSquare() {
        // js/model.js Board.createInitialBoard().toFen(), without its "- - 0 1" tail.
        val webFen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
        assertEquals(webFen, Fen.format(Board.createInitialBoard()))
        val board = Board.createInitialBoard()
        // A few squares named the way model.js places them: Position(row, col).
        assertEquals(PieceType.GENERAL to PieceColor.BLACK, board.getPiece(Position(0, 4))!!.let { it.type to it.color })
        assertEquals(PieceType.GENERAL to PieceColor.RED, board.getPiece(Position(9, 4))!!.let { it.type to it.color })
        assertEquals(PieceType.CANNON to PieceColor.RED, board.getPiece(Position(7, 7))!!.let { it.type to it.color })
        assertEquals(PieceType.HORSE to PieceColor.BLACK, board.getPiece(Position(0, 7))!!.let { it.type to it.color })
    }

    @Test
    fun movesRoundTripThroughTheWireInBothDirections() {
        for (row in 0..9) for (col in 0..8) {
            val from = Position(row, col)
            val to = Position(9 - row, 8 - col).takeIf { it != from } ?: Position(row, (col + 1) % 9)
            val wire = OnlineProtocol.encodeMove(from, to)
            assertEquals(mapOf("fromRow" to row, "fromCol" to col, "toRow" to to.row, "toCol" to to.col), wire)
            // What Firebase hands back: the same keys, numbers as Long, plus the server time.
            val back = OnlineProtocol.decodeMove(wire.mapValues { (it.value as Int).toLong() } + ("t" to 1_700_000_000_123L))
            assertEquals(WireMove(from, to, 1_700_000_000_123L), back)
        }
    }

    @Test
    fun aWebOpeningPlaysOutOnTheAppBoard() {
        // 炮二平五 马8进7 马二进三 车9平8 炮五进四 马2进3 炮五退二, as js/online.js writes them
        // (Firebase numbers are Long), the fifth move capturing the central soldier.
        val web = listOf(
            mapOf("fromRow" to 7L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 4L, "t" to 1000L),
            mapOf("fromRow" to 0L, "fromCol" to 7L, "toRow" to 2L, "toCol" to 6L, "t" to 4000L),
            mapOf("fromRow" to 9L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 6L, "t" to 6000L),
            mapOf("fromRow" to 0L, "fromCol" to 8L, "toRow" to 0L, "toCol" to 7L),
            mapOf("fromRow" to 7L, "fromCol" to 4L, "toRow" to 3L, "toCol" to 4L, "t" to 12000L),
            mapOf("fromRow" to 0L, "fromCol" to 1L, "toRow" to 2L, "toCol" to 2L, "t" to 15000L),
            mapOf("fromRow" to 3L, "fromCol" to 4L, "toRow" to 5L, "toCol" to 4L, "t" to 19000L)
        )
        val moves = OnlineProtocol.decodeMoves(web)
        assertEquals(7, moves.size)
        val board = Board.createInitialBoard()
        for ((i, wire) in moves.withIndex()) {
            assertEquals(OnlineProtocol.moverOf(i), board.currentPlayer)
            val legal = board.getAllLegalMoves().firstOrNull { it.from == wire.from && it.to == wire.to }
            assertNotNull("move $i ${wire.from}->${wire.to} should be legal", legal)
            if (i == 4) assertEquals(PieceType.SOLDIER, legal!!.capturedPiece?.type)
            board.makeMoveInPlace(legal!!)
        }
        assertEquals(PieceType.CANNON, board.getPiece(Position(5, 4))?.type)
        assertEquals(PieceType.HORSE, board.getPiece(Position(2, 2))?.type)
        assertNull(board.getPiece(Position(3, 4)))
        assertEquals(PieceColor.BLACK, board.currentPlayer)
    }

    @Test
    fun aBlackPlayersTurnedBoardMapsBackToTheSameSquares() {
        // Turned round, black's general sits at the bottom middle and red's chariot at the top right.
        assertEquals(Position(9, 4), BoardOrientation.toScreen(Position(0, 4), flipped = true))
        assertEquals(Position(0, 8), BoardOrientation.toScreen(Position(9, 0), flipped = true))
        assertEquals(Position(0, 4), BoardOrientation.fromScreen(9, 4, flipped = true))
        for (row in 0..9) for (col in 0..8) {
            val pos = Position(row, col)
            assertEquals(pos, BoardOrientation.toScreen(pos, flipped = false))
            val screen = BoardOrientation.toScreen(pos, flipped = true)
            assertTrue(screen.isValid())
            assertEquals(pos, BoardOrientation.fromScreen(screen.row, screen.col, flipped = true))
        }
        // A tap on the turned board sends the move in red's orientation: black's horse second from the
        // left on the bottom row (screen 9,1) going up to screen 7,2 is 马8进7 = (0,7) -> (2,6) on the wire.
        val from = BoardOrientation.fromScreen(9, 1, flipped = true)
        val to = BoardOrientation.fromScreen(7, 2, flipped = true)
        assertEquals(Position(0, 7), from)
        assertEquals(Position(2, 6), to)
        assertEquals(mapOf("fromRow" to 0, "fromCol" to 7, "toRow" to 2, "toCol" to 6), OnlineProtocol.encodeMove(from, to))
    }

    @Test
    fun brokenMovesAreRefused() {
        assertNull(OnlineProtocol.decodeMove(null))
        assertNull(OnlineProtocol.decodeMove("7,7-7,4"))
        assertNull(OnlineProtocol.decodeMove(mapOf("fromRow" to 7L, "fromCol" to 7L, "toRow" to 7L)))
        assertNull(OnlineProtocol.decodeMove(mapOf("fromRow" to 10L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 4L)))
        assertNull(OnlineProtocol.decodeMove(mapOf("fromRow" to 7L, "fromCol" to 9L, "toRow" to 7L, "toCol" to 4L)))
        assertNull(OnlineProtocol.decodeMove(mapOf("fromRow" to 7.5, "fromCol" to 7L, "toRow" to 7L, "toCol" to 4L)))
        assertNull(OnlineProtocol.decodeMove(mapOf("fromRow" to 7L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 7L)))
        // Whole-number doubles are fine (a JSON number may arrive either way).
        assertEquals(Position(7, 7), OnlineProtocol.decodeMove(mapOf("fromRow" to 7.0, "fromCol" to 7L, "toRow" to 7L, "toCol" to 4L))?.from)
    }

    @Test
    fun moveListsReadTheSameAsListOrMapAndStopAtAGap() {
        val m0 = mapOf("fromRow" to 7L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 4L)
        val m1 = mapOf("fromRow" to 0L, "fromCol" to 7L, "toRow" to 2L, "toCol" to 6L)
        val m2 = mapOf("fromRow" to 9L, "fromCol" to 7L, "toRow" to 7L, "toCol" to 6L)
        assertEquals(3, OnlineProtocol.decodeMoves(listOf(m0, m1, m2)).size)
        assertEquals(3, OnlineProtocol.decodeMoves(mapOf("2" to m2, "0" to m0, "1" to m1)).size)
        assertEquals(1, OnlineProtocol.decodeMoves(mapOf("0" to m0, "2" to m2)).size)
        assertEquals(1, OnlineProtocol.decodeMoves(listOf(m0, null, m2)).size)
        assertEquals(0, OnlineProtocol.decodeMoves(null).size)
        assertEquals(0, OnlineProtocol.decodeMoves("nonsense").size)
    }

    @Test
    fun evenMovesAreRedsAndColoursHaveTheWebsNames() {
        assertEquals(PieceColor.RED, OnlineProtocol.moverOf(0))
        assertEquals(PieceColor.BLACK, OnlineProtocol.moverOf(1))
        assertEquals(PieceColor.RED, OnlineProtocol.moverOf(42))
        assertEquals("red", OnlineProtocol.colorKey(PieceColor.RED))
        assertEquals("black", OnlineProtocol.colorKey(PieceColor.BLACK))
        assertEquals(PieceColor.BLACK, OnlineProtocol.parseColor("black"))
        assertNull(OnlineProtocol.parseColor("blue"))
        assertNull(OnlineProtocol.parseColor(null))
    }

    // ── Results ──

    @Test
    fun resultsUseTheWebsTypesAndRoundTrip() {
        assertEquals(mapOf("type" to "checkmate", "winner" to "red"),
            OnlineProtocol.encodeResult(WireResult(ResultType.CHECKMATE, PieceColor.RED)))
        assertEquals(mapOf("type" to "stalemate"), OnlineProtocol.encodeResult(WireResult(ResultType.STALEMATE, null)))
        assertEquals(mapOf("type" to "perpetualCheck", "winner" to "black"),
            OnlineProtocol.encodeResult(WireResult(ResultType.PERPETUAL_CHECK, PieceColor.BLACK)))
        assertEquals(mapOf("type" to "repetition"), OnlineProtocol.encodeResult(WireResult(ResultType.REPETITION, null)))
        for (type in ResultType.values()) for (winner in listOf(PieceColor.RED, PieceColor.BLACK, null)) {
            val result = WireResult(type, winner)
            assertEquals(result, OnlineProtocol.decodeResult(OnlineProtocol.encodeResult(result)))
        }
        // What the web writes when red resigns, with the server's time of it.
        assertEquals(WireResult(ResultType.RESIGN, PieceColor.BLACK, 1_791_652_000_000L),
            OnlineProtocol.decodeResult(mapOf("type" to "resign", "winner" to "black", "t" to 1_791_652_000_000L)))
        assertEquals(WireResult(ResultType.RESIGN, PieceColor.BLACK), OnlineProtocol.decodeResult(mapOf("type" to "resign", "winner" to "black")))
        assertNull(OnlineProtocol.decodeResult(mapOf("type" to "abandon")))
        assertNull(OnlineProtocol.decodeResult(null))
    }

    // ── Clocks ──

    @Test
    fun eachSideIsChargedTheIntervalsItWasOnTheMove() {
        // Start at 10 s; red moves at 13 s, black at 20 s, red at 21 s; now 30 s, black to move.
        val (red, black) = OnlineProtocol.sideTimes(10_000, listOf(13_000L, 20_000L, 21_000L), now = 30_000)
        assertEquals(3_000 + 1_000, red)
        assertEquals(7_000 + 9_000, black)
    }

    @Test
    fun clocksStopWithTheGameAndSkipWhatTheyCannotKnow() {
        // Over (now = null): nothing after the last move.
        assertEquals(3_000L to 7_000L, OnlineProtocol.sideTimes(10_000, listOf(13_000L, 20_000L), now = null))
        // A move with no timestamp (an older web page): the intervals on either side are left out.
        assertEquals(3_000L to 0L, OnlineProtocol.sideTimes(10_000, listOf(13_000L, null, 25_000L), now = null))
        // No start time: red's first interval is unknown.
        assertEquals(0L to 7_000L, OnlineProtocol.sideTimes(null, listOf(13_000L, 20_000L), now = null))
        // Before the first move red's clock runs from the start.
        assertEquals(4_000L to 0L, OnlineProtocol.sideTimes(10_000, emptyList(), now = 14_000))
        // A server clock that steps back never charges negative time.
        assertEquals(0L to 0L, OnlineProtocol.sideTimes(10_000, listOf(9_000L), now = 8_000))
    }
}
