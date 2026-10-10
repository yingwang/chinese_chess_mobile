package com.yingwang.chinesechess

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yingwang.chinesechess.ai.ChessAI
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.ai.PikafishEngine
import com.yingwang.chinesechess.ai.Review
import com.yingwang.chinesechess.ai.WeakPlay
import com.yingwang.chinesechess.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controls the game flow and AI interactions
 */
class GameController(
    private val context: Context,
    aiDifficulty: AIDifficulty = AIDifficulty.PROFESSIONAL,
    private val audio: GameAudioManager
) {
    private val difficulty: AIDifficulty = aiDifficulty
    /** Bumped whenever the position is reset, so a search finished against an old game is dropped. */
    private var gameGeneration = 0
    /** One conversation with the engine process at a time. */
    private val engineMutex = Mutex()

    /**
     * Engine view of the position from red's side: centipawns, or moves to mate
     * (positive = red mates). Null when the engine is unavailable.
     */
    data class Evaluation(val cpRed: Int?, val mateRed: Int?)
    /**
     * [candidates] above 1 makes the level choose among that many engine moves instead of
     * always the best (see [com.yingwang.chinesechess.ai.WeakPlay]); [spreadCp] is how far
     * from the best it is willing to stray.
     */
    enum class AIDifficulty(val pikafishDepth: Int, val candidates: Int = 1, val spreadCp: Int = 0) {
        NOVICE(4, candidates = 6, spreadCp = 100),
        LEARNER(4, candidates = 6, spreadCp = 60),
        BEGINNER(3),
        INTERMEDIATE(6),
        ADVANCED(10),
        PROFESSIONAL(15),
        MASTER(20),
        GRANDMASTER(0)        // Pikafish unlimited depth
    }

    enum class GameMode {
        PLAYER_VS_PLAYER,
        PLAYER_VS_AI,
        AI_VS_AI,
        /** Against a friend over the network (MainActivity and online.OnlineSession carry the moves). */
        ONLINE
    }

    /**
     * The clocks of an online game, worked out from the server's time of each move so that both
     * players see the same; replaces the local [GameClock] while one is set.
     */
    interface ClockSource {
        fun elapsed(side: PieceColor): Long
        /** The side whose time is running, or null once the game is over. */
        fun running(): PieceColor?
    }

    private var board = Board.createInitialBoard()
    private var initialBoard = Board.createInitialBoard()
    private var gameMode = GameMode.PLAYER_VS_AI
    /**
     * The side the engine plays. In an online game, the opponent's side: no engine plays it, but
     * everything that asks which side is the player's (the result, the review) reads it the same way.
     */
    private var aiColor = PieceColor.BLACK
    private val online: Boolean get() = gameMode == GameMode.ONLINE
    /** Set for an online game; see [ClockSource]. */
    var onlineClock: ClockSource? = null
    /** The study being solved, if this is endgame practice. */
    private var endgame: EndgameStudy? = null
    private val inEndgame: Boolean get() = endgame != null
    /** Set when the result is announced; cleared by anything that resets or rewinds the game. */
    private var gameOver = false
    private val fallbackAI: ChessAI = ChessAI(maxDepth = 3, timeLimit = 2000, quiescenceDepth = 2)
    private var pikafishEngine: PikafishEngine? = null

    private var moveHistory = mutableListOf<Move>()
    private var positionHashes = mutableListOf<Long>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** Each side's thinking time; see [syncClock] for when it runs. */
    private val clock = GameClock(SystemClock::elapsedRealtime)
    private var redScore = 0
    private var blackScore = 0
    private val redCapturedPieces = mutableListOf<Piece>()
    private val blackCapturedPieces = mutableListOf<Piece>()

    // Replay state
    private var replayMode = false
    private var replayIndex = 0
    private var replayMoves = listOf<Move>()

    var onBoardUpdated: ((Board) -> Unit)? = null
    var onEvaluationUpdated: ((Evaluation?) -> Unit)? = null
    var onGameOver: ((GameResult) -> Unit)? = null
    var onAIThinking: ((Boolean) -> Unit)? = null
    var onMoveCompleted: ((Move) -> Unit)? = null
    var onStatsUpdated: ((GameStats) -> Unit)? = null
    var onMoveAnimationRequested: ((Move, Board) -> Unit)? = null
    /** The player made as many moves as the study allows without mating. */
    var onEndgameFailed: ((EndgameStudy) -> Unit)? = null

    data class GameStats(
        val redScore: Int,
        val blackScore: Int,
        val moveNumber: Int,
        val redCapturedPieces: List<Piece> = emptyList(),
        val blackCapturedPieces: List<Piece> = emptyList()
    )

    sealed class GameResult {
        data class Checkmate(val winner: PieceColor) : GameResult()
        object Stalemate : GameResult()
        data class PerpetualCheck(val winner: PieceColor) : GameResult()
        object RepetitionDraw : GameResult()
    }

    fun setGameMode(mode: GameMode, aiColor: PieceColor = PieceColor.BLACK) {
        this.gameMode = mode
        this.aiColor = aiColor
    }

    fun startNewGame() {
        gameGeneration++
        interruptBackground()
        notes.clear()
        gameOver = false
        replayMode = false
        endgame = null
        board = Board.createInitialBoard()
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        clock.reset()
        syncClock()
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)

        // Trigger AI move if needed
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }
    }

    fun getCurrentBoard(): Board = board

    /** Position the current game started from (standard, or an endgame study). */
    fun getInitialBoard(): Board = initialBoard

    fun getMoveHistory(): List<Move> = moveHistory.toList()

    fun makePlayerMove(move: Move): Boolean {
        if (replayMode) return false
        // Online, only your own pieces, on your own turn, while the game is on.
        if (online && (gameOver || board.currentPlayer != aiColor.opposite())) return false

        // Validate move
        val legalMoves = board.getAllLegalMoves()
        if (move !in legalMoves) {
            return false
        }

        playMove(move)

        // Check game over
        if (checkGameOver()) {
            return true
        }

        val study = endgame
        if (study != null && endgameMovesLeft() <= 0) {
            gameOver = true
            syncClock()
            audio.playGameOverSound()
            onEndgameFailed?.invoke(study)
            return true
        }

        // AI's turn; its search reports the evaluation, otherwise ask for one.
        if (shouldAIMove()) {
            makeAIMove()
        } else {
            refreshEvaluation()
        }

        return true
    }

    /**
     * The opponent's move in an online game, as it came over the network. Played like one made
     * on this board (sound, animation, result), if it is legal and theirs to make; false otherwise.
     */
    fun applyRemoteMove(from: Position, to: Position): Boolean {
        if (!online || gameOver || replayMode) return false
        if (board.currentPlayer != aiColor) return false
        val move = board.getAllLegalMoves().firstOrNull { it.from == from && it.to == to } ?: return false
        playMove(move)
        checkGameOver()
        return true
    }

    /**
     * Sets up an online game with [myColor] as the player's side and plays [moves] onto the
     * opening without sound or animation: a new game, or one picked up again after the app
     * was away. Returns how many of the moves were legal and played; it stops at the first that
     * is not.
     */
    fun startOnlineGame(myColor: PieceColor, moves: List<Pair<Position, Position>> = emptyList()): Int {
        gameGeneration++
        interruptBackground()
        notes.clear()
        gameOver = false
        replayMode = false
        endgame = null
        gameMode = GameMode.ONLINE
        aiColor = myColor.opposite()
        board = Board.createInitialBoard()
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        var played = 0
        for ((from, to) in moves) {
            val move = board.getAllLegalMoves().firstOrNull { it.from == from && it.to == to } ?: break
            move.capturedPiece?.let { captured ->
                if (move.piece.color == PieceColor.RED) {
                    redScore += captured.type.baseValue
                    redCapturedPieces.add(captured)
                } else {
                    blackScore += captured.type.baseValue
                    blackCapturedPieces.add(captured)
                }
            }
            board.makeMoveInPlace(move)
            moveHistory.add(move)
            positionHashes.add(board.getPositionHash())
            played++
        }
        clock.reset()
        syncClock()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)
        return played
    }

    /**
     * After [startOnlineGame] picked a game up again: if its moves already ended it (the app went
     * away before the result was out), announces the result through [onGameOver] as usual.
     */
    fun settleOnlineGame(): Boolean = online && !gameOver && checkGameOver()

    /** An online game ended away from this board (a resignation, or the result the other side wrote). */
    fun endOnlineGame() {
        if (!online) return
        gameOver = true
        syncClock()
    }

    /** The player's side in an online game. */
    fun onlineColor(): PieceColor = aiColor.opposite()

    /** Sound, animation, the move on the board and everything that follows it, but not the result. */
    private fun playMove(move: Move) {
        // Play appropriate sound
        if (move.capturedPiece != null) {
            audio.playCaptureSound()
        } else {
            audio.playMoveSound()
        }

        // Update score and captured pieces if capturing
        if (move.capturedPiece != null) {
            val captureValue = move.capturedPiece.type.baseValue
            if (move.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(move.capturedPiece)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(move.capturedPiece)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(move, board.copy())

        // Make the move
        board.makeMoveInPlace(move)
        moveHistory.add(move)
        positionHashes.add(board.getPositionHash())
        syncClock()

        // Check for check condition and play sound
        if (board.isInCheck(board.currentPlayer)) {
            audio.playCheckSound()
        }

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(move)
        updateStats()
    }

    private fun shouldAIMove(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> false
            GameMode.PLAYER_VS_AI -> board.currentPlayer == aiColor
            GameMode.AI_VS_AI -> true
            GameMode.ONLINE -> false
        }
    }

    /**
     * The engine, started on first use. Starting runs to the end even if the caller is cancelled
     * meanwhile (the background analysis often is, when the player resumes a game or moves while
     * the engine is still loading): giving up half way left a running engine process behind,
     * about 230 MB each, and the next caller started another.
     */
    private suspend fun ensurePikafish(): PikafishEngine? {
        pikafishEngine?.let { return it }
        return withContext(NonCancellable) {
            val engine = PikafishEngine(context)
            try {
                engine.start()
                pikafishEngine = engine
                engine
            } catch (e: Exception) {
                Log.e("GameController", "Failed to start Pikafish", e)
                engine.close()
                null
            }
        }
    }

    fun makeAIMove() {
        if (board.isCheckmate() || board.isStalemate()) return
        // The AI's turn comes before looking back over the game.
        interruptBackground()

        onAIThinking?.invoke(true)

        coroutineScope.launch {
            try {
                val generation = gameGeneration
                val thinkStart = System.currentTimeMillis()
                val sideToMove = board.currentPlayer
                var (move, score) = searchBestMove(null)

                // Shuffling: the engine happily walks a piece back and forth when nothing is
                // pressing, which reads as dithering. If the chosen move revisits a position
                // or reverses its own last move, search again with those moves off the table
                // and take the alternative when it costs little. A third occurrence of a
                // position is avoided regardless of score.
                if (move != null && (timesSeenAfter(move) >= 1 || looksLikeShuffling(move, sideToMove))) {
                    val forced = timesSeenAfter(move) >= 2
                    val fresh = board.getAllLegalMoves().filter {
                        timesSeenAfter(it) == 0 && !looksLikeShuffling(it, sideToMove)
                    }
                    val (alternative, altScore) = searchBestMove(fresh)
                    if (alternative != null && (forced || acceptableAlternative(score, altScore))) {
                        move = alternative
                        score = altScore
                    }
                }

                if (move != null) {
                    // A reply that lands the instant the player lifts a finger reads as a glitch;
                    // hold it back so every AI move takes at least MIN_THINK_MS.
                    val elapsed = System.currentTimeMillis() - thinkStart
                    if (elapsed < MIN_THINK_MS) delay(MIN_THINK_MS - elapsed)
                    if (generation == gameGeneration) {
                        // The root score of the search is the engine's view of the line it chose.
                        onEvaluationUpdated?.invoke(toRedPerspective(score, sideToMove))
                        applyAIMove(move)
                    }
                }
            } finally {
                onAIThinking?.invoke(false)
            }
        }
    }

    /**
     * Engine search, optionally restricted to [candidates]; null means every legal move.
     * Returns the move and, when Pikafish produced it, its root score for the side to move.
     */
    private suspend fun searchBestMove(candidates: List<Move>?): Pair<Move?, PikafishEngine.Score?> {
        if (candidates != null && candidates.isEmpty()) return null to null
        // An endgame study is only a study against the best defence, whatever the level.
        val depth = when {
            inEndgame -> ENDGAME_DEFENCE_DEPTH
            difficulty.pikafishDepth > 0 -> difficulty.pikafishDepth
            else -> 0
        }
        val timeMs = if (depth == 0) 10000L else 0L  // 棋圣: 10s unlimited
        val fromEngine = engineMutex.withLock {
            val engine = ensurePikafish() ?: return@withLock null
            if (difficulty.candidates > 1 && !inEndgame) {
                val options = engine.findCandidates(board, depth, difficulty.candidates, candidates)
                val choice = WeakPlay.pick(options, difficulty.spreadCp) ?: return@withLock null
                return@withLock choice to options.first { it.first == choice }.second
            }
            val move = engine.findBestMove(board, depth = depth, moveTimeMs = timeMs, searchMoves = candidates)
            if (move != null) move to engine.lastScore else null
        }
        if (fromEngine != null) return fromEngine
        return fallbackAI.findBestMove(board, moveHistory, allowedMoves = candidates) to null
    }

    private fun toRedPerspective(score: PikafishEngine.Score?, sideToMove: PieceColor): Evaluation? {
        if (score == null) return null
        val sign = if (sideToMove == PieceColor.RED) 1 else -1
        return Evaluation(cpRed = score.cp?.let { it * sign }, mateRed = score.mate?.let { it * sign })
    }

    /**
     * Shallow engine evaluation of [target] (the live board by default), published through
     * [onEvaluationUpdated] unless the game moved on while it ran.
     */
    private fun refreshEvaluation(target: Board = board) {
        // No engine help while an online game is being played; a review afterwards is fine.
        if (online && !gameOver) return
        val snapshot = target.copy()
        if (snapshot.isCheckmate() || snapshot.isStalemate()) return
        val generation = gameGeneration
        val moveCount = moveHistory.size
        val replayAt = replayIndex
        coroutineScope.launch {
            val score = engineMutex.withLock {
                // Scrubbing through a replay queues a look at every position passed; only the
                // one still on the board is worth the engine's time.
                if (generation != gameGeneration || moveCount != moveHistory.size || replayAt != replayIndex) return@withLock null
                val engine = ensurePikafish() ?: return@withLock null
                engine.evaluate(snapshot, depth = 10)
            }
            if (generation == gameGeneration && moveCount == moveHistory.size && replayAt == replayIndex) {
                onEvaluationUpdated?.invoke(toRedPerspective(score, snapshot.currentPlayer))
            }
        }
    }

    private suspend fun applyAIMove(finalMove: Move) {
        if (finalMove.capturedPiece != null) audio.playCaptureSound() else audio.playMoveSound()

        val captured = finalMove.capturedPiece
        if (captured != null) {
            val captureValue = captured.type.baseValue
            if (finalMove.piece.color == PieceColor.RED) {
                redScore += captureValue
                redCapturedPieces.add(captured)
            } else {
                blackScore += captureValue
                blackCapturedPieces.add(captured)
            }
        }

        // Request animation before mutating board state
        onMoveAnimationRequested?.invoke(finalMove, board.copy())

        board.makeMoveInPlace(finalMove)
        moveHistory.add(finalMove)
        positionHashes.add(board.getPositionHash())
        syncClock()

        if (board.isInCheck(board.currentPlayer)) audio.playCheckSound()

        onBoardUpdated?.invoke(board)
        onMoveCompleted?.invoke(finalMove)
        updateStats()

        if (!checkGameOver() && gameMode == GameMode.AI_VS_AI) {
            delay(500) // Brief pause for visualization
            makeAIMove()
        }
    }

    /** How many times the position after [move] has already occurred in this game. */
    private fun timesSeenAfter(move: Move): Int {
        val testBoard = board.copy()
        testBoard.makeMoveInPlace(move)
        val hash = testBoard.getPositionHash()
        return positionHashes.count { it == hash }
    }

    /** True if [move] undoes [side]'s previous move or repeats the one before that. */
    private fun looksLikeShuffling(move: Move, side: PieceColor): Boolean {
        val own = moveHistory.filter { it.piece.color == side }
        val last = own.lastOrNull() ?: return false
        if (last.from == move.to && last.to == move.from) return true
        val twoAgo = own.getOrNull(own.size - 2) ?: return false
        return twoAgo.from == move.from && twoAgo.to == move.to
    }

    /**
     * Whether an alternative to the engine's first choice is worth playing to avoid a
     * repetition: never give up a mate, never walk into one, otherwise within 60 cp.
     * Without scores (fallback search) any alternative is fine.
     */
    private fun acceptableAlternative(best: PikafishEngine.Score?, alt: PikafishEngine.Score?): Boolean {
        if (best == null || alt == null) return true
        best.mate?.let { return it < 0 }          // getting mated anyway: anything goes; mating: keep it
        alt.mate?.let { return it > 0 }           // alternative mates: fine; gets mated: no
        val bestCp = best.cp ?: return true
        val altCp = alt.cp ?: return true
        return altCp >= bestCp - 60
    }

    fun isGameOver(): Boolean = gameOver

    private fun checkGameOver(): Boolean {
        // Repetition detection: same position 3 times. If the side to move is in check there, the
        // opponent has been checking perpetually and loses.
        val repeated = positionHashes.count { it == positionHashes.last() } >= 3
        val result = when {
            board.isCheckmate() -> GameResult.Checkmate(board.currentPlayer.opposite())
            board.isStalemate() -> GameResult.Stalemate
            repeated && board.isInCheck(board.currentPlayer) -> GameResult.PerpetualCheck(board.currentPlayer)
            repeated -> GameResult.RepetitionDraw
            else -> null
        }
        // Settled before the result is announced: the game-over dialog asks whether the game is
        // over, and the clocks stop on the last move.
        gameOver = result != null
        syncClock()
        if (result == null) return false
        audio.playGameOverSound()
        onGameOver?.invoke(result)
        return true
    }

    fun undoLastMove(): Boolean {
        if (online) return false
        gameGeneration++
        gameOver = false
        if (moveHistory.isEmpty()) return false

        // In player vs AI mode, undo two moves (player and AI)
        val movesToUndo = if (gameMode == GameMode.PLAYER_VS_AI) 2 else 1

        repeat(movesToUndo.coerceAtMost(moveHistory.size)) {
            moveHistory.removeAt(moveHistory.size - 1)
        }
        interruptBackground()
        notes.keys.removeAll { it > moveHistory.size }

        // Rebuild board and captured pieces from history
        board = initialBoard.copy()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        for (move in moveHistory) {
            if (move.capturedPiece != null) {
                val captureValue = move.capturedPiece.type.baseValue
                if (move.piece.color == PieceColor.RED) {
                    redScore += captureValue
                    redCapturedPieces.add(move.capturedPiece)
                } else {
                    blackScore += captureValue
                    blackCapturedPieces.add(move.capturedPiece)
                }
            }
            board.makeMoveInPlace(move)
            positionHashes.add(board.getPositionHash())
        }
        // The clocks keep the time already spent; the side now to move is charged from here on
        // (GameClock).
        syncClock()

        onBoardUpdated?.invoke(board)
        updateStats()
        refreshEvaluation()
        return true
    }

    fun startEndgame(study: EndgameStudy) {
        gameGeneration++
        interruptBackground()
        notes.clear()
        gameOver = false
        replayMode = false
        endgame = study
        board = Fen.parse(study.fen)
        initialBoard = board.copy()
        moveHistory.clear()
        positionHashes.clear()
        positionHashes.add(board.getPositionHash())
        fallbackAI.clearCache()
        redScore = 0
        blackScore = 0
        redCapturedPieces.clear()
        blackCapturedPieces.clear()
        gameMode = GameMode.PLAYER_VS_AI
        aiColor = board.currentPlayer.opposite()
        // A study is timed like any game, from zero on every attempt.
        clock.reset()
        syncClock()
        onBoardUpdated?.invoke(board)
        updateStats()
        onEvaluationUpdated?.invoke(null)
        if (!shouldAIMove()) refreshEvaluation()
    }

    fun getEndgame(): EndgameStudy? = endgame

    /** Moves the player still has to deliver mate in the current study. */
    fun endgameMovesLeft(): Int {
        val study = endgame ?: return 0
        val player = aiColor.opposite()
        return study.mateIn - moveHistory.count { it.piece.color == player }
    }

    /**
     * Sets the study up again and opens its solution in the replay, one move at a time.
     * Leaving the replay leaves the study at its start, ready to try.
     */
    fun showSolution(study: EndgameStudy) {
        startEndgame(study)
        val moves = mutableListOf<Move>()
        val walk = initialBoard.copy()
        for (uci in study.solution) {
            if (uci.length < 4) break
            val from = Position(9 - (uci[1] - '0'), uci[0] - 'a')
            val to = Position(9 - (uci[3] - '0'), uci[2] - 'a')
            val piece = walk.getPiece(from) ?: break
            val move = Move(from, to, piece, walk.getPiece(to))
            moves.add(move)
            walk.makeMoveInPlace(move)
        }
        if (moves.isEmpty()) return
        replayMode = true
        syncClock()
        replayMoves = moves
        replayIndex = 0
        rebuildBoardToIndex(0)
    }

    fun isEndgameMode(): Boolean = inEndgame

    // --- Replay Mode ---

    fun enterReplayMode(): Boolean {
        if (moveHistory.isEmpty()) return false
        // The game holds still while it is looked at: an AI move in flight is dropped and an
        // AI-vs-AI game pauses, so the moves under review do not change (see exitReplayMode).
        gameGeneration++
        interruptBackground()
        replayMode = true
        syncClock()
        replayMoves = moveHistory.toList()
        replayIndex = replayMoves.size
        return true
    }

    fun exitReplayMode() {
        gameGeneration++
        replayMode = false
        replayMoves = emptyList()
        replayIndex = 0
        syncClock()
        onBoardUpdated?.invoke(board)
        // Pick the game up where it stopped: the AI moves on if it is its turn.
        if (!gameOver && !board.isCheckmate() && !board.isStalemate() && shouldAIMove()) makeAIMove()
        else scheduleBackgroundAnalysis()
    }

    fun isInReplayMode() = replayMode

    fun replayStepBack(): Boolean {
        if (!replayMode || replayIndex <= 0) return false
        replayIndex--
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayStepForward(): Boolean {
        if (!replayMode || replayIndex >= replayMoves.size) return false
        replayIndex++
        rebuildBoardToIndex(replayIndex)
        return true
    }

    fun replayToStart() {
        if (!replayMode) return
        replayIndex = 0
        rebuildBoardToIndex(0)
    }

    fun replayToEnd() {
        if (!replayMode) return
        replayIndex = replayMoves.size
        rebuildBoardToIndex(replayIndex)
    }

    fun getReplayIndex(): Int = replayIndex

    fun getReplayLength(): Int = replayMoves.size

    /** Shows the position after [index] moves of the game being replayed. */
    fun replayGoTo(index: Int) {
        if (!replayMode) return
        replayIndex = index.coerceIn(0, replayMoves.size)
        rebuildBoardToIndex(replayIndex)
    }

    fun getReplayInfo(): String =
        if (replayMode) context.getString(R.string.replay_progress, replayIndex, replayMoves.size) else ""

    private fun rebuildBoardToIndex(index: Int) {
        val tempBoard = initialBoard.copy()
        for (i in 0 until index) {
            tempBoard.makeMoveInPlace(replayMoves[i])
        }
        val lastReplayMove = if (index > 0) replayMoves[index - 1] else null
        onBoardUpdated?.invoke(tempBoard)
        if (lastReplayMove != null) {
            onMoveCompleted?.invoke(lastReplayMove)
        }
        refreshEvaluation(tempBoard)
    }

    fun getAIStats(): String {
        return "Difficulty: $difficulty, Engine: ${if (pikafishEngine != null) "Pikafish" else "fallback"}"
    }

    fun getGameMode(): GameMode = gameMode

    fun getAIColor(): PieceColor = aiColor

    fun getDifficulty(): AIDifficulty = difficulty

    /** Time [side] has used so far in this game. */
    fun clockTime(side: PieceColor): Long =
        onlineClock?.takeIf { online }?.elapsed(side) ?: clock.elapsed(side)

    /** Both sides' time together. */
    fun totalClockTime(): Long = clockTime(PieceColor.RED) + clockTime(PieceColor.BLACK)

    /** The side whose clock runs while the app is in the foreground; null when both are stopped. */
    fun clockTurn(): PieceColor? {
        val source = onlineClock
        if (online && source != null) return if (gameOver) null else source.running()
        return clock.turn
    }

    /**
     * Only the side to move is charged, and nobody once the game is over or while a replay (or a
     * study's solution) is on the board. The pause for the background is separate ([setInForeground]).
     */
    private fun syncClock() {
        val stopped = replayMode || gameOver || board.isCheckmate() || board.isStalemate()
        clock.setTurn(if (stopped) null else board.currentPlayer)
    }

    /**
     * A game nobody has moved in starts its clock afresh: the time spent on the dialogs shown at
     * launch is not the first player's thinking.
     */
    fun restartClockIfUnplayed() {
        if (moveHistory.isNotEmpty()) return
        clock.reset()
        syncClock()
    }

    fun isPlayerTurn(): Boolean {
        return when (gameMode) {
            GameMode.PLAYER_VS_PLAYER -> true
            GameMode.PLAYER_VS_AI -> board.currentPlayer != aiColor
            GameMode.AI_VS_AI -> false
            GameMode.ONLINE -> !gameOver && board.currentPlayer != aiColor
        }
    }

    private fun updateStats() {
        val stats = GameStats(
            redScore = redScore,
            blackScore = blackScore,
            moveNumber = moveHistory.size,
            redCapturedPieces = redCapturedPieces.toList(),
            blackCapturedPieces = blackCapturedPieces.toList()
        )
        onStatsUpdated?.invoke(stats)
        // Every change to the game ends here; the engine can look back while the player thinks.
        scheduleBackgroundAnalysis()
    }

    fun saveGame(context: Context): Boolean {
        // An online game lives in the database and is picked up again from there (OnlineStore).
        if (online) return false
        try {
            val json = JSONObject()
            // The position the game started from, so an endgame study resumes on its own
            // board instead of having its moves replayed onto the opening.
            json.put("initialFen", Fen.format(initialBoard))
            endgame?.let { json.put("endgameId", it.id) }
            json.put("gameMode", gameMode.name)
            json.put("aiColor", aiColor.name)
            json.put("difficulty", difficulty.name)
            clock.writeTo(json)
            // The one total that saves before 2.4.8 kept, for an older version reading this one.
            json.put("elapsedMs", clock.total())

            val movesArray = JSONArray()
            for (move in moveHistory) {
                val moveJson = JSONObject()
                moveJson.put("fromRow", move.from.row)
                moveJson.put("fromCol", move.from.col)
                moveJson.put("toRow", move.to.row)
                moveJson.put("toCol", move.to.col)
                moveJson.put("pieceType", move.piece.type.name)
                moveJson.put("pieceColor", move.piece.color.name)
                if (move.capturedPiece != null) {
                    moveJson.put("capturedType", move.capturedPiece.type.name)
                    moveJson.put("capturedColor", move.capturedPiece.color.name)
                }
                movesArray.put(moveJson)
            }
            json.put("moves", movesArray)

            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            prefs.edit().putString("saved_game", json.toString()).apply()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun loadGame(context: Context): Boolean {
        gameGeneration++
        interruptBackground()
        notes.clear()
        gameOver = false
        try {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return false
            val json = JSONObject(jsonStr)

            // Restore game mode
            gameMode = GameMode.valueOf(json.getString("gameMode"))
            aiColor = PieceColor.valueOf(json.getString("aiColor"))

            // Replay all moves from where the game started
            board = json.optString("initialFen").takeIf { it.isNotEmpty() }?.let { Fen.parse(it) }
                ?: Board.createInitialBoard()
            initialBoard = board.copy()
            endgame = json.optString("endgameId").takeIf { it.isNotEmpty() }?.let { EndgameStudies.byId(context, it) }
            replayMode = false
            moveHistory.clear()
            positionHashes.clear()
            positionHashes.add(board.getPositionHash())
            redScore = 0
            blackScore = 0
            redCapturedPieces.clear()
            blackCapturedPieces.clear()

            val movesArray = json.getJSONArray("moves")
            for (i in 0 until movesArray.length()) {
                val moveJson = movesArray.getJSONObject(i)
                val from = Position(moveJson.getInt("fromRow"), moveJson.getInt("fromCol"))
                val to = Position(moveJson.getInt("toRow"), moveJson.getInt("toCol"))
                val piece = board.getPiece(from) ?: continue
                val capturedPiece = board.getPiece(to)
                val move = Move(from, to, piece, capturedPiece)

                if (capturedPiece != null) {
                    val captureValue = capturedPiece.type.baseValue
                    if (piece.color == PieceColor.RED) {
                        redScore += captureValue
                        redCapturedPieces.add(capturedPiece)
                    } else {
                        blackScore += captureValue
                        blackCapturedPieces.add(capturedPiece)
                    }
                }

                board.makeMoveInPlace(move)
                moveHistory.add(move)
                positionHashes.add(board.getPositionHash())
            }

            // Each clock resumes where it stopped (from zero for a save that has no clocks).
            val (redMs, blackMs) = GameClock.savedTimes(json)
            clock.reset(redMs, blackMs)
            syncClock()
            onBoardUpdated?.invoke(board)
            updateStats()
            if (!shouldAIMove()) refreshEvaluation()

            // Trigger AI move if it's AI's turn
            if (shouldAIMove()) {
                makeAIMove()
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    companion object {
        /** Floor on how long an AI move appears to take, so replies never look instant. */
        private const val MIN_THINK_MS = 900L

        /** Depth for scoring each position of a finished game, and for checking its mistakes. */
        private const val REVIEW_DEPTH = 8
        private const val REVIEW_BEST_DEPTH = 12
        /** At most this many suspected mistakes are checked at [REVIEW_BEST_DEPTH], worst first. */
        private const val REVIEW_VERIFY_LIMIT = 6
        /** How long the engine looks at a position the player stops on in a review, as for a hint. */
        private const val DEEP_LOOK_MS = 2000L

        /** Search depth for the defending side of an endgame study. */
        private const val ENDGAME_DEFENCE_DEPTH = 12

        /** Difficulty stored with the saved game, so resuming rebuilds the same opponent. */
        fun savedDifficulty(context: Context): AIDifficulty? {
            val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("saved_game", null) ?: return null
            return try {
                AIDifficulty.valueOf(JSONObject(jsonStr).getString("difficulty"))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun hasSavedGame(context: Context): Boolean {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        return prefs.contains("saved_game")
    }

    fun deleteSavedGame(context: Context) {
        val prefs = context.getSharedPreferences("chess_save", Context.MODE_PRIVATE)
        prefs.edit().remove("saved_game").apply()
    }

    // ── Analysis while the game is played ──
    // While the player thinks the engine would sit idle, so it looks at the positions of the game
    // instead, newest first, at the depth the review checks mistakes with. The review afterwards
    // then has most positions ready, and deeper than a review from scratch could afford. The AI's
    // turn, a hint, a replay and leaving the app all come first: the search in hand is stopped
    // and the engine handed over at once.

    /** Search results by the number of moves played before the position. */
    private val notes = mutableMapOf<Int, Pair<PikafishEngine.Score?, Move?>>()
    private var backgroundJob: Job? = null
    private var backgroundSearching = false
    private var backgroundAllowed = true

    /**
     * Called as the app goes to the background and comes back: the clocks stop and pick up again
     * where they were, and the engine does no work of its own while the app is out of sight.
     */
    fun setInForeground(inForeground: Boolean) {
        clock.setPaused(!inForeground)
        backgroundAllowed = inForeground
        if (inForeground) scheduleBackgroundAnalysis() else interruptBackground()
    }

    private fun positionAfter(index: Int): Board {
        val position = initialBoard.copy()
        for (i in 0 until index.coerceAtMost(moveHistory.size)) position.makeMoveInPlace(moveHistory[i])
        return position
    }

    private fun scheduleBackgroundAnalysis() {
        if (!backgroundAllowed || replayMode || backgroundJob?.isActive == true) return
        if (online && !gameOver) return
        backgroundJob = coroutineScope.launch {
            while (isActive) {
                if (replayMode || (!gameOver && shouldAIMove())) break
                val index = (moveHistory.size downTo 0).firstOrNull { it !in notes } ?: break
                val generation = gameGeneration
                val position = positionAfter(index)
                val result = if (position.isCheckmate()) PikafishEngine.Score(cp = null, mate = 0) to null
                else engineMutex.withLock {
                    val engine = ensurePikafish() ?: return@withLock null
                    backgroundSearching = true
                    try {
                        engine.analyse(position, depth = REVIEW_BEST_DEPTH)
                    } finally {
                        backgroundSearching = false
                    }
                } ?: break
                if (generation == gameGeneration && index <= moveHistory.size) notes[index] = result
            }
        }
    }

    /** Hands the engine back at once: stops the background search in hand and the loop. */
    private fun interruptBackground() {
        val job = backgroundJob ?: return
        backgroundJob = null
        if (job.isActive) {
            job.cancel()
            if (backgroundSearching) pikafishEngine?.stopSearch()
        }
    }

    /**
     * The move by [player] that cost the most, judged by the engine, with what it should
     * have been. [delta] is how far the position fell in centipawns from the player's side.
     */
    data class Mistake(val index: Int, val played: Move, val better: Move?, val before: Evaluation?, val after: Evaluation?, val delta: Int)

    /**
     * Red-side readings and the engine's move for every position of a game, the moves a deeper
     * search confirmed as mistakes or blunders, and the player's costliest move among those.
     */
    data class Analysis(
        val evals: List<Evaluation?>,
        /** The engine's choice in each position (none in the last). */
        val best: List<Move?>,
        /** Moves confirmed by the deeper search, by the index of the position they were played from. */
        val verdicts: Map<Int, Review.Verdict>,
        val mistake: Mistake?
    )

    private var analysisJob: Job? = null

    /** Stops a review in progress, so the game it belongs to can have the engine back. */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
    }

    /**
     * Searches one position, taking the engine for that search alone, so a game picked up again
     * meanwhile (or a hint) waits for one search at most, never for a whole review. Null when
     * the engine is unavailable.
     */
    private suspend fun searchPosition(position: Board, depth: Int = 0, moveTimeMs: Long = 0): Pair<PikafishEngine.Score?, Move?>? {
        if (position.isCheckmate()) return PikafishEngine.Score(cp = null, mate = 0) to null
        return engineMutex.withLock {
            val engine = ensurePikafish() ?: return@withLock null
            engine.analyse(position, depth, moveTimeMs)
        }
    }

    /**
     * Reviews the game on the board. Every position is searched at [REVIEW_DEPTH] for the graph
     * and the engine's move; a search that shallow is good to a few percent of the game, so the
     * moves it takes for mistakes (the [player]'s, or both sides' when [player] is null) are
     * searched again at [REVIEW_BEST_DEPTH], before and after, and only those the deeper look
     * agrees on are named. [onProgress] reports each search, [onDone] the result (null when the
     * engine is unavailable); neither is called once [cancelAnalysis] has stopped the review.
     * Both run off the main thread.
     */
    fun analyzeGame(
        player: PieceColor?,
        onProgress: (verifying: Boolean, done: Int, total: Int) -> Unit,
        onDone: (Analysis?) -> Unit
    ) {
        cancelAnalysis()
        interruptBackground()
        val moves = moveHistory.toList()
        val start = initialBoard.copy()
        // Positions already looked at while the game was played need no search now.
        val noted = notes.toMap()
        analysisJob = coroutineScope.launch {
            // makeMove keeps the side to move (it is for trying moves out); these need the turn to pass.
            val positions = mutableListOf(start.copy())
            for (m in moves) positions.add(positions.last().copy().also { it.makeMoveInPlace(m) })

            val scores = ArrayList<PikafishEngine.Score?>(positions.size)
            val best = ArrayList<Move?>(positions.size)
            val deep = BooleanArray(positions.size) { it in noted }
            val missing = positions.indices.count { !deep[it] }
            var searched = 0
            for ((i, position) in positions.withIndex()) {
                val (score, move) = noted[i] ?: searchPosition(position, depth = REVIEW_DEPTH) ?: run {
                    onDone(null)
                    return@launch
                }
                scores.add(score)
                best.add(move)
                if (!deep[i]) onProgress(false, ++searched, missing)
            }

            // What each move cost its player, as a share of the game (see Review): judged by the
            // share, not centipawns, because in a lost game the biggest centipawn drops come
            // after the game was already gone.
            fun cost(i: Int): Double? {
                val before = scores[i] ?: return null
                val after = scores[i + 1] ?: return null
                return Review.cost(before, after)
            }
            val suspects = moves.indices
                .filter { i -> (player == null || moves[i].piece.color == player) && (cost(i) ?: 0.0) >= Review.MISTAKE }
                .sortedByDescending { cost(it) }
                .take(REVIEW_VERIFY_LIMIT)
            val verdicts = mutableMapOf<Int, Review.Verdict>()
            val confirmed = mutableMapOf<Int, Double>()
            for ((k, i) in suspects.withIndex()) {
                if (!deep[i]) {
                    val before = searchPosition(positions[i], depth = REVIEW_BEST_DEPTH) ?: break
                    before.first?.let { scores[i] = it }
                    before.second?.let { best[i] = it }
                    deep[i] = true
                }
                if (!deep[i + 1]) {
                    val after = searchPosition(positions[i + 1], depth = REVIEW_BEST_DEPTH) ?: break
                    after.first?.let { scores[i + 1] = it }
                    deep[i + 1] = true
                }
                onProgress(true, k + 1, suspects.size)
                val c = cost(i) ?: continue
                val verdict = Review.verdict(c)
                if (verdict == Review.Verdict.MISTAKE || verdict == Review.Verdict.BLUNDER) {
                    verdicts[i] = verdict
                    confirmed[i] = c
                }
            }

            val evals = scores.mapIndexed { i, sc ->
                if (sc?.mate == 0) {
                    // The side to move is mated: a decided game for the other side.
                    Evaluation(cpRed = null, mateRed = if (positions[i].currentPlayer == PieceColor.RED) -1 else 1)
                } else toRedPerspective(sc, positions[i].currentPlayer)
            }
            val worst = if (player == null) null else confirmed.maxByOrNull { it.value }?.key
            val mistake = worst?.let { index ->
                // After the move it is the opponent's turn, so their score is the player's loss.
                val delta = WeakPlay.comparable(scores[index]!!) + WeakPlay.comparable(scores[index + 1]!!)
                Mistake(
                    index = index,
                    played = moves[index],
                    better = best[index]?.takeIf { it.from != moves[index].from || it.to != moves[index].to },
                    before = evals[index],
                    after = evals[index + 1],
                    delta = delta
                )
            }
            onDone(Analysis(evals, best, verdicts, mistake))
        }
    }

    /**
     * A longer look at the position after [index] moves of the game being replayed, as long as
     * a hint takes: the engine's move there. Null when the engine is unavailable.
     */
    fun deepLook(index: Int, onDone: (Move?) -> Unit): Job {
        val position = initialBoard.copy()
        for (i in 0 until index.coerceAtMost(moveHistory.size)) position.makeMoveInPlace(moveHistory[i])
        return coroutineScope.launch {
            onDone(searchPosition(position, moveTimeMs = DEEP_LOOK_MS)?.second)
        }
    }

    fun getHint(callback: (Move?) -> Unit) {
        if (board.isCheckmate() || board.isStalemate()) {
            callback(null)
            return
        }

        onAIThinking?.invoke(true)
        interruptBackground()
        coroutineScope.launch {
            try {
                val sideToMove = board.currentPlayer
                val index = moveHistory.size
                val generation = gameGeneration
                val fromEngine = engineMutex.withLock {
                    val engine = ensurePikafish() ?: return@withLock null
                    val move = engine.findBestMove(board, moveTimeMs = 2000)
                    if (move != null) move to engine.lastScore else null
                }
                if (fromEngine != null) {
                    // A hint is as deep a look as the review takes; keep it for the review.
                    if (generation == gameGeneration && index == moveHistory.size) notes[index] = fromEngine.second to fromEngine.first
                    onEvaluationUpdated?.invoke(toRedPerspective(fromEngine.second, sideToMove))
                    callback(fromEngine.first)
                } else {
                    callback(fallbackAI.findBestMove(board, moveHistory))
                }
            } finally {
                onAIThinking?.invoke(false)
                scheduleBackgroundAnalysis()
            }
        }
    }

    fun destroy() {
        coroutineScope.cancel()
        pikafishEngine?.close()
        pikafishEngine = null
    }
}
