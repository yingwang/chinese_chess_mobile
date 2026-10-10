package com.yingwang.chinesechess

import com.yingwang.chinesechess.model.PieceColor.BLACK
import com.yingwang.chinesechess.model.PieceColor.RED
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GameClockTest {

    /** A hand-wound monotonic clock, in milliseconds. */
    private var t = 1_000_000L
    private val clock = GameClock { t }

    private fun assertTimes(red: Long, black: Long) {
        assertEquals("red", red, clock.elapsed(RED))
        assertEquals("black", black, clock.elapsed(BLACK))
    }

    @Test
    fun onlyTheSideToMoveIsCharged() {
        clock.reset(turn = RED)
        t += 3_000
        assertTimes(3_000, 0)
        assertEquals(RED, clock.running)
    }

    @Test
    fun aMoveBanksTheMoverAndStartsTheOther() {
        clock.reset(turn = RED)
        t += 4_000
        clock.setTurn(BLACK)
        t += 1_500
        assertTimes(4_000, 1_500)
        clock.setTurn(RED)
        t += 250
        assertTimes(4_250, 1_500)
        assertEquals(5_750, clock.total())
    }

    @Test
    fun handingTheMoveToTheSideAlreadyOnItChangesNothing() {
        clock.reset(turn = RED)
        t += 1_000
        clock.setTurn(RED)
        clock.setTurn(RED)
        t += 1_000
        assertTimes(2_000, 0)
    }

    @Test
    fun theBackgroundStopsBothClocksAndTheyResumeWithoutAJump() {
        clock.reset(turn = RED)
        t += 1_000
        clock.setPaused(true)
        assertNull(clock.running)
        t += 3_600_000 // an hour in the background
        assertTimes(1_000, 0)
        clock.setPaused(false)
        t += 500
        assertTimes(1_500, 0)
    }

    @Test
    fun aMoveMadeInTheBackgroundChargesNobodyUntilTheAppReturns() {
        // The AI finishes its search after the player has left the app.
        clock.reset(turn = BLACK)
        t += 2_000
        clock.setPaused(true)
        t += 10_000
        clock.setTurn(RED)
        t += 50_000
        assertTimes(0, 2_000)
        clock.setPaused(false)
        t += 700
        assertTimes(700, 2_000)
    }

    @Test
    fun gameOverStopsBothClocksForGood() {
        clock.reset(turn = RED)
        t += 9_000
        clock.setTurn(BLACK)
        t += 1_000
        clock.setTurn(null)
        t += 60_000
        assertTimes(9_000, 1_000)
        // Leaving and coming back does not restart a finished game.
        clock.setPaused(true)
        clock.setPaused(false)
        t += 60_000
        assertTimes(9_000, 1_000)
        assertNull(clock.running)
    }

    @Test
    fun aReplayStopsTheClocksAndLeavingItPicksUpTheSideToMove() {
        clock.reset(turn = BLACK)
        t += 2_000
        clock.setTurn(null) // into the replay
        t += 30_000
        clock.setTurn(BLACK) // back to the game
        t += 1_000
        assertTimes(0, 3_000)
    }

    @Test
    fun undoAgainstTheAiKeepsTheTimeSpentAndGivesNothingBack() {
        // The player (red) thinks 10 s, the AI replies in 2 s, the player looks 5 s and takes
        // both moves back: still red to move, so red's clock simply runs on.
        clock.reset(turn = RED)
        t += 10_000
        clock.setTurn(BLACK)
        t += 2_000
        clock.setTurn(RED)
        t += 5_000
        clock.setTurn(RED) // the take-back
        assertTimes(15_000, 2_000)
        t += 3_000
        assertTimes(18_000, 2_000)
        // Every millisecond counted once, to one side.
        assertEquals(20_000, clock.total())
    }

    @Test
    fun undoBetweenTwoPlayersHandsTheClockBackWithoutCountingTwice() {
        clock.reset(turn = RED)
        t += 6_000
        clock.setTurn(BLACK)
        t += 4_000
        clock.setTurn(RED) // black's move is taken back: red to move again
        assertTimes(6_000, 4_000)
        clock.setTurn(BLACK) // and red's too
        clock.setTurn(RED)
        assertTimes(6_000, 4_000)
        t += 1_000
        assertTimes(7_000, 4_000)
        assertEquals(11_000, clock.total())
    }

    @Test
    fun aNewGameStartsFromZero() {
        clock.reset(turn = RED)
        t += 5_000
        clock.reset(turn = RED)
        assertTimes(0, 0)
        t += 1_000
        assertTimes(1_000, 0)
    }

    @Test
    fun savedClocksIncludeTheRunInProgressAndResumeWhereTheyStopped() {
        clock.reset(turn = RED)
        t += 65_000
        clock.setTurn(BLACK)
        t += 12_345
        val json = JSONObject()
        clock.writeTo(json)
        assertEquals(65_000, json.getLong(GameClock.KEY_RED))
        assertEquals(12_345, json.getLong(GameClock.KEY_BLACK))

        // The process dies; a new one restores the game, black to move.
        t = 42L
        val restored = GameClock { t }
        val (red, black) = GameClock.savedTimes(JSONObject(json.toString()))
        restored.reset(red, black, turn = BLACK)
        t += 655
        assertEquals(65_000, restored.elapsed(RED))
        assertEquals(13_000, restored.elapsed(BLACK))
    }

    @Test
    fun aSaveFromBeforeTheClocksStartsBothAtZero() {
        // 2.4.7 and earlier kept one wall-clock total and nothing per side.
        val old = JSONObject("""{"gameMode":"PLAYER_VS_AI","aiColor":"BLACK","elapsedMs":5400000,"moves":[]}""")
        assertEquals(0L to 0L, GameClock.savedTimes(old))
        // Nonsense in a save does not make a clock run backwards.
        val bad = JSONObject("""{"redClockMs":-5,"blackClockMs":"x"}""")
        assertEquals(0L to 0L, GameClock.savedTimes(bad))
    }

    @Test
    fun aClockSourceThatStepsBackNeverTakesTimeAway() {
        clock.reset(turn = RED)
        t += 1_000
        clock.setTurn(BLACK)
        t -= 5_000
        assertTimes(1_000, 0)
        clock.setTurn(RED)
        assertTimes(1_000, 0)
        assertFalse(clock.paused)
    }

    @Test
    fun timesReadAsMinutesThenHours() {
        assertEquals("00:00", GameClock.format(0))
        assertEquals("00:00", GameClock.format(999))
        assertEquals("01:05", GameClock.format(65_000))
        assertEquals("59:59", GameClock.format(3_599_999))
        assertEquals("1:00:00", GameClock.format(3_600_000))
        assertEquals("1:02:05", GameClock.format(3_725_000))
        assertEquals("00:00", GameClock.format(-10))
    }
}
