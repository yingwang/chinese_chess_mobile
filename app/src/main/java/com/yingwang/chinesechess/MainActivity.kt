package com.yingwang.chinesechess

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yingwang.chinesechess.GameController.AIDifficulty
import com.yingwang.chinesechess.GameController.GameMode
import com.yingwang.chinesechess.ai.Review
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.model.Piece
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView
import com.yingwang.chinesechess.ui.EvalBarView
import com.yingwang.chinesechess.ui.EvalGraphView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Mates farther away than this are shown as a plain "winning"/"losing". */
        private const val MATE_HINT_LIMIT = 3
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_CHALLENGE = "challenge"
        private const val KEY_SHOW_EVAL = "show_eval"
        private const val KEY_APPEARANCE = "appearance_mode"
        private const val DEFAULT_APPEARANCE = AppCompatDelegate.MODE_NIGHT_YES
        /** Room the board leaves under itself for the move strip (activity_main.xml). */
        private const val BOARD_RESERVE_DP = 56
        /** How long the player stays on a review position before the engine takes a longer look. */
        private const val DEEP_LOOK_DELAY_MS = 600L
        /** How often the running clock is redrawn; well under a second, so no second is skipped. */
        private const val CLOCK_REFRESH_MS = 200L
    }

    private lateinit var boardView: BoardView

    // Header
    private lateinit var redCard: View
    private lateinit var blackCard: View
    private lateinit var redTurnDot: View
    private lateinit var blackTurnDot: View
    private lateinit var redRoleText: TextView
    private lateinit var blackRoleText: TextView
    private lateinit var redCapturedLayout: LinearLayout
    private lateinit var blackCapturedLayout: LinearLayout
    private lateinit var redClockText: TextView
    private lateinit var blackClockText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var gameModeText: TextView
    private lateinit var evalBar: EvalBarView
    private lateinit var evalText: TextView
    private lateinit var evalRow: View
    private var evaluation: GameController.Evaluation? = null
    private var lastStats: GameController.GameStats? = null

    // Status pill
    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: LinearLayout
    private lateinit var thinkingDot1: View
    private lateinit var thinkingDot2: View
    private lateinit var thinkingDot3: View

    // Review: the panel under the replay bar, and the analysis of the game it shows, kept
    // while the game's moves are unchanged.
    private lateinit var reviewPanel: View
    private lateinit var buttonContainer: View
    private lateinit var reviewInfoText: TextView
    private lateinit var reviewGraph: EvalGraphView
    private var review: GameController.Analysis? = null
    private var reviewedMoves: List<com.yingwang.chinesechess.model.Move>? = null
    private var reviewRunning = false
    private var reviewProgress: String? = null

    private lateinit var moveHistoryText: TextView
    private lateinit var moveStrip: View
    private lateinit var replayBar: View
    private lateinit var replayProgressText: TextView
    private lateinit var hintButton: Button
    private lateinit var undoButton: Button
    private lateinit var moreButton: Button

    private var isMuted = false
    private val settings by lazy { getSharedPreferences("chess_settings", MODE_PRIVATE) }
    private lateinit var audioManager: GameAudioManager
    private lateinit var gameController: GameController
    private var thinkingAnimator: AnimatorSet? = null
    private var aiThinking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark unless the player picks otherwise: the app has always been dark, and players
        // updating from it should not find it turned pale.
        delegate.localNightMode = settings.getInt(KEY_APPEARANCE, DEFAULT_APPEARANCE)
        super.onCreate(savedInstanceState)
        updateSystemBarAppearance()
        setContentView(R.layout.activity_main)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        audioManager = GameAudioManager(this)
        // Mute used to reset on every launch, so the guqin came back each time.
        isMuted = settings.getBoolean("muted", false)
        audioManager.setMuted(isMuted)
        // A saved game remembers its difficulty; start the controller with it so resuming
        // faces the same opponent. Otherwise the level last chosen, and on the very first
        // launch 初级 until the player says otherwise: starting everyone at 专业 meant a
        // newcomer's first game was a rout.
        val startDifficulty = GameController.savedDifficulty(this) ?: preferredDifficulty() ?: AIDifficulty.BEGINNER
        gameController = GameController(this, startDifficulty, audioManager)
        initViews()
        setupGameControllerCallbacks()
        gameController.startNewGame()
        startClockUpdates()

        if (!gameController.hasSavedGame(this) && preferredDifficulty() == null) {
            showDifficultyDialog(firstRun = true)
        }

        if (gameController.hasSavedGame(this)) {
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.resume_title)
                .setMessage(R.string.resume_message)
                .setPositiveButton(R.string.resume_continue) { _, _ ->
                    gameController.loadGame(this)
                    updateGameModeDisplay()
                    boardView.highlightMove(gameController.getMoveHistory().lastOrNull())
                }
                .setNegativeButton(R.string.new_game) { _, _ ->
                    gameController.deleteSavedGame(this)
                }
                // A fresh game's first clock starts once the question is answered, not under it.
                .setOnDismissListener { gameController.restartClockIfUnplayed() }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isMuted) audioManager.startBackgroundMusic()
        gameController.setInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        audioManager.pauseBackgroundMusic()
        // The clocks stop and the engine does no work while the app is out of sight; stopped
        // first, so the save has both clocks to the moment the app left.
        gameController.setInForeground(false)
        if (gameController.getMoveHistory().isNotEmpty()) {
            gameController.saveGame(this)
        }
    }

    private fun updateSystemBarAppearance() {
        val light = resources.getBoolean(R.bool.chess_light_system_bars)
        enableEdgeToEdge(
            statusBarStyle = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::gameController.isInitialized) return
        // Keep the controller, unfinished study and replay position alive when changing theme.
        thinkingAnimator?.cancel()
        setTheme(R.style.Theme_ChineseChess)
        window.setBackgroundDrawableResource(R.drawable.chess_table_background)
        updateSystemBarAppearance()
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        initViews()
        setupGameControllerCallbacks()
        boardView.setBoard(gameController.getCurrentBoard())
        if (gameController.isInReplayMode()) gameController.replayGoTo(gameController.getReplayIndex())
        else boardView.highlightMove(gameController.getMoveHistory().lastOrNull())
        lastStats?.let { updateGameStats(it) }
        evalBar.setEvaluation(evaluation?.cpRed, evaluation?.mateRed)
        currentReview()?.let { analysis ->
            reviewGraph.setData(analysis.evals.map { it?.let { value -> value.cpRed to value.mateRed } },
                reviewMarks(analysis), analysis.mistake?.index, gameController.getReplayIndex())
        }
        updateGameModeDisplay()
        updateReplayBar()
        updateStatus()
        if (aiThinking) {
            aiThinkingIndicator.visibility = View.VISIBLE
            startThinkingAnimation()
            undoButton.isEnabled = false
            hintButton.isEnabled = false
            statusText.setText(R.string.ai_thinking)
        }
    }

    private fun showThemeDialog() {
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val selected = modes.indexOf(settings.getInt(KEY_APPEARANCE, DEFAULT_APPEARANCE)).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(arrayOf(getString(R.string.theme_light), getString(R.string.theme_dark),
                getString(R.string.theme_system)), selected) { dialog, index ->
                dialog.dismiss()
                settings.edit().putInt(KEY_APPEARANCE, modes[index]).apply()
                delegate.localNightMode = modes[index]
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        redCard = findViewById(R.id.redCard)
        blackCard = findViewById(R.id.blackCard)
        redTurnDot = findViewById(R.id.redTurnDot)
        blackTurnDot = findViewById(R.id.blackTurnDot)
        redRoleText = findViewById(R.id.redRoleText)
        blackRoleText = findViewById(R.id.blackRoleText)
        redCapturedLayout = findViewById(R.id.redCapturedPieces)
        blackCapturedLayout = findViewById(R.id.blackCapturedPieces)
        redClockText = findViewById(R.id.redClockText)
        blackClockText = findViewById(R.id.blackClockText)
        moveCountText = findViewById(R.id.moveCountText)
        gameModeText = findViewById(R.id.gameModeText)
        evalBar = findViewById(R.id.evalBar)
        evalText = findViewById(R.id.evalText)
        evalRow = findViewById(R.id.evalRow)
        applyEvalVisibility()
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        thinkingDot1 = findViewById(R.id.thinkingDot1)
        thinkingDot2 = findViewById(R.id.thinkingDot2)
        thinkingDot3 = findViewById(R.id.thinkingDot3)
        moveHistoryText = findViewById(R.id.moveHistoryText)
        moveStrip = findViewById(R.id.moveStrip)
        replayBar = findViewById(R.id.replayBar)
        replayProgressText = findViewById(R.id.replayProgressText)
        moveStrip.setOnClickListener { showFullHistory() }
        reviewPanel = findViewById(R.id.reviewPanel)
        buttonContainer = findViewById(R.id.buttonContainer)
        reviewInfoText = findViewById(R.id.reviewInfoText)
        reviewGraph = findViewById(R.id.reviewGraph)
        reviewGraph.setOnPickListener { index ->
            if (gameController.isInReplayMode()) {
                gameController.replayGoTo(index)
                updateReplayBar()
            }
        }
        findViewById<View>(R.id.reviewPrevMistake).setOnClickListener { jumpToMistake(-1) }
        findViewById<View>(R.id.reviewNextMistake).setOnClickListener { jumpToMistake(1) }
        boardView.onBlockedTouch = { toast(R.string.replay_blocked) }
        findViewById<View>(R.id.replayStartButton).setOnClickListener { gameController.replayToStart(); updateReplayBar() }
        findViewById<View>(R.id.replayPrevButton).setOnClickListener {
            if (!gameController.replayStepBack()) toast(R.string.replay_at_start)
            updateReplayBar()
        }
        findViewById<View>(R.id.replayNextButton).setOnClickListener {
            if (!gameController.replayStepForward()) toast(R.string.replay_at_end)
            updateReplayBar()
        }
        findViewById<View>(R.id.replayEndButton).setOnClickListener { gameController.replayToEnd(); updateReplayBar() }
        findViewById<View>(R.id.replayExitButton).setOnClickListener { exitReplay() }
        hintButton = findViewById(R.id.hintButton)
        undoButton = findViewById(R.id.undoButton)
        moreButton = findViewById(R.id.moreButton)

        moreButton.setOnClickListener { showMoreDialog() }

        undoButton.setOnClickListener {
            if (isRatedGame()) {
                toast(R.string.challenge_no_undo)
                return@setOnClickListener
            }
            if (gameController.getMoveHistory().isEmpty()) {
                toast(R.string.undo_none)
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.undo_confirm_title)
                .setMessage(R.string.undo_confirm_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (gameController.undoLastMove()) {
                        boardView.clearSelection()
                        boardView.highlightMove(gameController.getMoveHistory().lastOrNull())
                        toast(R.string.undo_done)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        hintButton.setOnClickListener {
            if (isRatedGame()) {
                toast(R.string.challenge_no_hint)
                return@setOnClickListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                return@setOnClickListener
            }
            gameController.getHint { move ->
                runOnUiThread {
                    if (move != null) {
                        boardView.highlightMove(move)
                        val text = notation(move, gameController.getCurrentBoard())
                        Snackbar.make(boardView, getString(R.string.hint_suggest, text), 4000).show()
                    } else {
                        Snackbar.make(boardView, R.string.hint_unavailable, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        updateScoreLines()
        moveCountText.text = getString(R.string.round_label, 0)
        updateClocks()
    }

    private fun setupGameControllerCallbacks() {
        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus()
                // No last move to mark on an empty game or at the start of a replay.
                if (gameController.getMoveHistory().isEmpty() ||
                    (gameController.isInReplayMode() && gameController.getReplayIndex() == 0)
                ) {
                    boardView.highlightMove(null)
                }
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread { showGameOver(result) }
        }

        gameController.onEndgameFailed = {
            runOnUiThread {
                updateStatus()
                showEndgameResult(solved = false)
            }
        }

        gameController.onAIThinking = { isThinking ->
            aiThinking = isThinking
            runOnUiThread {
                aiThinkingIndicator.visibility = if (isThinking) View.VISIBLE else View.GONE
                if (isThinking) startThinkingAnimation() else stopThinkingAnimation()
                undoButton.isEnabled = !isThinking
                hintButton.isEnabled = !isThinking
                statusText.text = if (isThinking) getString(R.string.ai_thinking) else getStatusText()
            }
        }

        gameController.onMoveCompleted = { move ->
            runOnUiThread {
                boardView.highlightMove(move)
                boardView.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }
        }

        gameController.onStatsUpdated = { stats ->
            runOnUiThread { updateGameStats(stats) }
        }

        gameController.onEvaluationUpdated = { eval ->
            runOnUiThread {
                evaluation = eval
                evalBar.setEvaluation(eval?.cpRed, eval?.mateRed)
                updateScoreLines()
            }
        }

        gameController.onMoveAnimationRequested = { move, preBoard ->
            runOnUiThread {
                boardView.animateMove(move, preBoard) {
                    boardView.setBoard(gameController.getCurrentBoard())
                }
            }
        }

        boardView.setOnMoveListener { move ->
            if (gameController.isInReplayMode()) {
                toast(R.string.replay_blocked)
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (gameController.makePlayerMove(move)) {
                boardView.clearSelection()
            } else {
                toast(R.string.illegal_move)
            }
        }

        updateGameModeDisplay()
    }

    // ── Header and status ──

    private fun sideName(color: PieceColor): String =
        getString(if (color == PieceColor.RED) R.string.red_side else R.string.black_side)

    private fun updateStatus() {
        statusText.text = getStatusText()
        val board = gameController.getCurrentBoard()
        val over = board.isCheckmate() || board.isStalemate()
        val redActive = !over && board.currentPlayer == PieceColor.RED
        val blackActive = !over && board.currentPlayer == PieceColor.BLACK
        redCard.setBackgroundResource(if (redActive) R.drawable.player_card_active else R.drawable.player_card)
        blackCard.setBackgroundResource(if (blackActive) R.drawable.player_card_active else R.drawable.player_card)
        redTurnDot.visibility = if (redActive) View.VISIBLE else View.INVISIBLE
        blackTurnDot.visibility = if (blackActive) View.VISIBLE else View.INVISIBLE
        updateClocks()
    }

    /**
     * Each card's clock: the time its side has used, the running one (the side to move, while
     * the game is live) in the accent and bold, the other quieter.
     */
    private fun updateClocks() {
        val running = gameController.clockTurn()
        showClock(redClockText, gameController.clockTime(PieceColor.RED), running == PieceColor.RED)
        showClock(blackClockText, gameController.clockTime(PieceColor.BLACK), running == PieceColor.BLACK)
    }

    private fun showClock(view: TextView, ms: Long, running: Boolean) {
        val text = GameClock.format(ms)
        if (view.text.toString() != text) view.text = text
        if (view.isActivated != running) {
            view.isActivated = running
            view.setTypeface(Typeface.SERIF, if (running) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun getStatusText(): String {
        val board = gameController.getCurrentBoard()
        val side = sideName(board.currentPlayer)
        return when {
            board.isCheckmate() -> getString(R.string.wins, sideName(board.currentPlayer.opposite()))
            board.isStalemate() -> getString(R.string.draw_short)
            board.isInCheck(board.currentPlayer) -> getString(R.string.in_check, side)
            gameController.isEndgameMode() && !gameController.isGameOver() ->
                getString(R.string.endgame_goal, gameController.getEndgame()?.mateIn ?: 0, gameController.endgameMovesLeft())
            else -> getString(R.string.side_to_move, side)
        }
    }

    /**
     * One reading of the position, from red's side, at the end of the evaluation bar; the
     * cards say what each side has taken. They used to show +0.2 and -0.2, the same number
     * twice, above a bar that showed it a third time.
     */
    private fun updateScoreLines() {
        val stats = lastStats
        val eval = evaluation
        // Without an engine reading the bar keeps the whole width rather than leave a gap.
        // Hidden, not gone, while there is no reading yet: the row keeps its height, so the board
        // under it does not jump when the first evaluation of a game arrives.
        // The placeholder is a real reading's text, since an empty line is shorter than one in
        // Chinese characters.
        evalText.visibility = if (eval == null) View.INVISIBLE else View.VISIBLE
        evalText.text = if (eval == null) getString(R.string.eval_even) else evalSummary(eval)
    }

    private fun evalSummary(eval: GameController.Evaluation): String {
        eval.mateRed?.let { mate ->
            // A mate count is a spoiler, so only a short one is spelled out.
            return when {
                mate > 0 && mate <= MATE_HINT_LIMIT -> getString(R.string.eval_red_mates, mate)
                mate < 0 && -mate <= MATE_HINT_LIMIT -> getString(R.string.eval_black_mates, -mate)
                mate > 0 -> getString(R.string.eval_red_winning)
                else -> getString(R.string.eval_black_winning)
            }
        }
        val pawns = (eval.cpRed ?: 0) / 100.0
        return when {
            pawns >= 0.3 -> getString(R.string.eval_red_better, String.format(Locale.US, "%.1f", pawns))
            pawns <= -0.3 -> getString(R.string.eval_black_better, String.format(Locale.US, "%.1f", -pawns))
            else -> getString(R.string.eval_even)
        }
    }

    private fun updateGameStats(stats: GameController.GameStats) {
        lastStats = stats
        updateScoreLines()
        updateClocks()
        // A round is a red move and the black reply, as in the move strip and the record.
        moveCountText.text = getString(R.string.round_label, (stats.moveNumber + 1) / 2)
        updateMoveHistory()
        // Each card shows the pieces its side has taken.
        updateCapturedRow(redCapturedLayout, stats.redCapturedPieces)
        updateCapturedRow(blackCapturedLayout, stats.blackCapturedPieces)
    }

    /**
     * The pieces a side has taken, as one line of text grouped by kind, most valuable first
     * (車×2  炮  卒×3): it stays readable however many there are.
     */
    private fun updateCapturedRow(container: LinearLayout, pieces: List<Piece>) {
        container.removeAllViews()
        if (pieces.isEmpty()) return
        val summary = pieces.groupBy { it.type }.entries.sortedByDescending { it.key.baseValue }
            .joinToString("  ") { (type, group) ->
                type.getDisplayName(group.first().color) + if (group.size > 1) "×${group.size}" else ""
            }
        container.addView(TextView(this).apply {
            text = summary
            typeface = Typeface.SERIF
            // In the colour of the side the pieces belonged to, as on the board.
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (pieces.first().color == PieceColor.RED) R.color.chess_red_side else R.color.chess_black_side
                )
            )
            gravity = (if (container.id == R.id.blackCapturedPieces) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            // One line of a fixed height, the text shrinking as captures mount up, so the header
            // (and the board under it) never moves when a piece is taken.
            maxLines = 1
            includeFontPadding = false
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.captured_row_height)
            )
        })
    }

    private fun startThinkingAnimation() {
        val dots = listOf(thinkingDot1, thinkingDot2, thinkingDot3)
        val animators = dots.mapIndexed { index, dot ->
            ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 200L
            }
        }
        thinkingAnimator = AnimatorSet().apply {
            playTogether(animators.map { it as android.animation.Animator })
            start()
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
    }

    /** The strip under the board shows only the latest round; the whole game is a tap away. */
    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            moveHistoryText.text = getString(R.string.history_empty)
            return
        }
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        val lastRoundStart = (moves.size - 1) / 2 * 2
        val round = notations.subList(lastRoundStart, moves.size).joinToString("  ")
        moveHistoryText.text = getString(R.string.history_round, lastRoundStart / 2 + 1, round)
    }

    private fun fullHistoryText(): String {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) return getString(R.string.history_empty)
        val history = StringBuilder()
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        moves.forEachIndexed { index, _ ->
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                history.append(String.format(Locale.US, "%2d. %s", moveNum, notations[index]))
            } else {
                history.append("    ").append(notations[index]).append('\n')
            }
        }
        return history.toString().trimEnd()
    }

    private fun showFullHistory() {
        val dp = resources.displayMetrics.density
        val text = TextView(this).apply {
            text = fullHistoryText()
            textSize = 15f
            typeface = Typeface.SERIF
            setLineSpacing(4 * dp, 1f)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_history_text))
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val scroll = ScrollView(this).apply { addView(text) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.history_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.replay_title) { _, _ -> toggleReplay() }
            .setNegativeButton(R.string.export) { _, _ -> exportMoveHistory() }
            .show()
    }

    /** Redraws the clocks while the app is in front, the only time they run. */
    private fun startClockUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    updateClocks()
                    delay(CLOCK_REFRESH_MS)
                }
            }
        }
    }

    private fun difficultyShortName(): String =
        resources.getStringArray(R.array.difficulty_short)[gameController.getDifficulty().ordinal]

    /** Short caption for the header. */
    private fun modeCaption(): String = when {
        gameController.isInReplayMode() -> getString(R.string.mode_replay)
        gameController.isEndgameMode() -> getString(R.string.mode_endgame)
        else -> when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> getString(R.string.mode_pvp)
            GameMode.PLAYER_VS_AI -> getString(
                if (isRatedGame()) R.string.mode_challenge else R.string.mode_pvai, difficultyShortName()
            )
            GameMode.AI_VS_AI -> getString(R.string.mode_aivai)
        }
    }

    /** Full description for the exported record. */
    private fun modeDescription(): String = when {
        gameController.isInReplayMode() -> getString(R.string.mode_replay_long)
        gameController.isEndgameMode() -> getString(R.string.mode_endgame_long)
        else -> when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> getString(R.string.mode_pvp_long)
            GameMode.PLAYER_VS_AI -> {
                val playerColor = getString(
                    if (gameController.getAIColor() == PieceColor.RED) R.string.black_short else R.string.red_short
                )
                getString(R.string.mode_pvai_long, playerColor, difficultyShortName())
            }
            GameMode.AI_VS_AI -> getString(R.string.mode_aivai_long)
        }
    }

    private fun updateGameModeDisplay() {
        findViewById<TextView>(R.id.screenTitle).setText(if (gameController.isInReplayMode()) R.string.jade_review_title else R.string.jade_game_title)
        gameModeText.text = modeCaption()
        val (redRole, blackRole) = when (gameController.getGameMode()) {
            GameMode.PLAYER_VS_PLAYER -> R.string.role_player to R.string.role_player
            GameMode.AI_VS_AI -> R.string.role_ai to R.string.role_ai
            GameMode.PLAYER_VS_AI ->
                if (gameController.getAIColor() == PieceColor.RED) R.string.role_ai to R.string.role_player
                else R.string.role_player to R.string.role_ai
        }
        redRoleText.setText(redRole)
        blackRoleText.setText(blackRole)
        // Challenge games keep the buttons in place but greyed, so the layout does not jump.
        val locked = isRatedGame()
        hintButton.alpha = if (locked) 0.4f else 1f
        undoButton.alpha = if (locked) 0.4f else 1f
        updateStatus()
    }

    // ── End of a game ──

    /**
     * The result, then something to do next: play again, look at the move that cost the most,
     * or, after a run of wins or losses at one level, move to the next level.
     */
    private fun showGameOver(result: GameController.GameResult) {
        val playerColor = gameController.getAIColor().opposite()
        if (gameController.isEndgameMode()) {
            showEndgameResult(solved = result is GameController.GameResult.Checkmate && result.winner == playerColor)
            return
        }
        val vsAI = gameController.getGameMode() == GameMode.PLAYER_VS_AI && !gameController.isEndgameMode()
        val playerScore = when (result) {
            is GameController.GameResult.Checkmate -> if (result.winner == playerColor) 1.0 else 0.0
            is GameController.GameResult.PerpetualCheck -> if (result.winner == playerColor) 1.0 else 0.0
            GameController.GameResult.Stalemate, GameController.GameResult.RepetitionDraw -> 0.5
        }
        val headline = when (result) {
            is GameController.GameResult.Checkmate -> getString(R.string.wins, sideName(result.winner))
            is GameController.GameResult.PerpetualCheck -> getString(R.string.perpetual_check_loss, sideName(result.winner))
            GameController.GameResult.Stalemate -> getString(R.string.draw)
            GameController.GameResult.RepetitionDraw -> getString(R.string.repetition_draw)
        }
        val message = StringBuilder(headline)
        if (vsAI && isRatedGame()) {
            val change = RatingSystem.recordGame(this, gameController.getDifficulty(), playerScore)
            val stats = RatingSystem.getStats(this)
            val sign = if (change >= 0) "+" else ""
            message.append(getString(R.string.rating_summary, stats.rating.toString(), "$sign$change", stats.rankTitle))
        }

        val builder = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.game_over)
            .setMessage(message)
            .setPositiveButton(R.string.play_again) { _, _ ->
                gameController.startNewGame()
                updateGameModeDisplay()
            }
        if (gameController.getMoveHistory().isNotEmpty()) {
            builder.setNegativeButton(R.string.review_game) { _, _ -> startReview() }
        }
        builder.setNeutralButton(R.string.close, null)
        builder.show()
    }

    /** The human player in this game, if exactly one side is human. */
    private fun humanSide(): PieceColor? =
        if (gameController.getGameMode() == GameMode.PLAYER_VS_AI) gameController.getAIColor().opposite() else null

    /**
     * Reviews the game on the board: replay it with the graph of how it went under the board,
     * opening at the player's costliest move with the better one drawn. The analysis takes a
     * while, so the replay opens at once, the panel counts the positions searched and fills in
     * when it is ready.
     */
    private fun startReview() {
        if (gameController.getMoveHistory().isEmpty()) {
            toast(R.string.replay_none)
            return
        }
        if (!gameController.isInReplayMode()) gameController.enterReplayMode()
        updateGameModeDisplay()
        currentReview()?.let { openReviewAt(it); return }
        val moves = gameController.getMoveHistory().toList()
        reviewRunning = true
        reviewProgress = null
        updateReplayBar()
        gameController.analyzeGame(
            humanSide(),
            onProgress = { verifying, done, total ->
                runOnUiThread {
                    if (!reviewRunning) return@runOnUiThread
                    reviewProgress = getString(if (verifying) R.string.review_verifying else R.string.review_progress, done, total)
                    if (gameController.isInReplayMode()) reviewInfoText.text = reviewProgress
                }
            },
            onDone = { analysis ->
                runOnUiThread {
                    if (!reviewRunning) return@runOnUiThread
                    reviewRunning = false
                    reviewProgress = null
                    if (analysis == null) {
                        Snackbar.make(boardView, R.string.analysis_unavailable, Snackbar.LENGTH_LONG).show()
                        updateReplayBar()
                        return@runOnUiThread
                    }
                    review = analysis
                    reviewedMoves = moves
                    deepLooks.clear()
                    deepLooksTried.clear()
                    reviewGraph.setData(
                        analysis.evals.map { e -> e?.let { it.cpRed to it.mateRed } },
                        reviewMarks(analysis),
                        analysis.mistake?.index,
                        gameController.getReplayIndex()
                    )
                    if (gameController.isInReplayMode() && currentReview() != null) openReviewAt(analysis) else updateReplayBar()
                }
            }
        )
    }

    /** Leaving the replay ends a review still being worked out, so the game gets the engine back. */
    private fun stopReview() {
        if (reviewRunning) gameController.cancelAnalysis()
        reviewRunning = false
        reviewProgress = null
        cancelDeepLook()
    }

    /** Moves the replay to the next marked move after the one on the board, or the one before. */
    private fun jumpToMistake(direction: Int) {
        val analysis = currentReview() ?: return
        val index = gameController.getReplayIndex()
        val marks = reviewMarks(analysis).keys
        val target = if (direction > 0) marks.filter { it > index }.minOrNull()
            else marks.filter { it < index }.maxOrNull()
        if (target == null) toast(R.string.review_no_mistake) else {
            gameController.replayGoTo(target)
            updateReplayBar()
        }
    }

    private fun openReviewAt(analysis: GameController.Analysis) {
        analysis.mistake?.let { gameController.replayGoTo(it.index) }
        updateReplayBar()
    }

    /** The analysis of the game on the board, if its moves have not changed since. */
    private fun currentReview(): GameController.Analysis? {
        val analysed = reviewedMoves ?: return null
        val moves = gameController.getMoveHistory()
        if (analysed.size != moves.size) return null
        if (analysed.indices.any { analysed[it].from != moves[it].from || analysed[it].to != moves[it].to }) return null
        return review
    }

    /**
     * The moves to mark on the graph: those the deeper search confirmed as mistakes or blunders.
     * Keyed by the position each was played from, the one whose line under the board explains
     * that move and whose arrow shows the better one.
     */
    private fun reviewMarks(analysis: GameController.Analysis): Map<Int, EvalGraphView.Mark> =
        analysis.verdicts.mapValues { (_, verdict) ->
            if (verdict == Review.Verdict.BLUNDER) EvalGraphView.Mark.BLUNDER else EvalGraphView.Mark.MISTAKE
        }

    // A longer look at the position the player stops on: its move replaces the shallow one.
    private val deepLooks = mutableMapOf<Int, com.yingwang.chinesechess.model.Move>()
    private val deepLooksTried = mutableSetOf<Int>()
    private var deepLookJob: kotlinx.coroutines.Job? = null
    private var deepLookIndex: Int? = null
    private val deepLookHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val deepLookStart = Runnable {
        val index = deepLookIndex ?: return@Runnable
        if (!gameController.isInReplayMode() || gameController.getReplayIndex() != index) return@Runnable
        deepLookJob = gameController.deepLook(index) { move ->
            runOnUiThread {
                deepLooksTried += index
                if (move != null) deepLooks[index] = move
                if (deepLookIndex == index) deepLookIndex = null
                if (gameController.isInReplayMode() && gameController.getReplayIndex() == index) updateReviewPanel()
            }
        }
    }

    /** Starts a longer look at [index] once the player has stayed there a moment. */
    private fun scheduleDeepLook(index: Int) {
        if (deepLookIndex == index || index in deepLooksTried) return
        cancelDeepLook()
        deepLookIndex = index
        deepLookHandler.postDelayed(deepLookStart, DEEP_LOOK_DELAY_MS)
    }

    private fun cancelDeepLook() {
        deepLookHandler.removeCallbacks(deepLookStart)
        deepLookJob?.cancel()
        deepLookJob = null
        deepLookIndex = null
    }

    /**
     * Shows the review panel while an analysed game is replayed, with the line for the position
     * on the board and the engine's move from it drawn as an arrow. The bottom bar makes way
     * for the panel, so the graph has room to read.
     */
    private fun updateReviewPanel() {
        val analysis = currentReview()
        val replaying = gameController.isInReplayMode()
        val show = replaying && (analysis != null || reviewRunning)
        if (show != (reviewPanel.visibility == View.VISIBLE)) {
            reviewPanel.visibility = if (show) View.VISIBLE else View.GONE
            buttonContainer.visibility = if (show) View.GONE else View.VISIBLE
            // With the bottom bar gone the board's lower edge is the screen's, so it leaves room
            // for the strip and the panel; on a tall phone it keeps its full width anyway.
            val dp = resources.displayMetrics.density
            val reserve = (BOARD_RESERVE_DP * dp).toInt() +
                if (show) resources.getDimensionPixelSize(R.dimen.review_panel_height) + (14 * dp).toInt() else 0
            (boardView.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = reserve
            boardView.requestLayout()
        }
        val index = gameController.getReplayIndex()
        val moreToCome = index < gameController.getReplayLength()
        val engineMove = if (index in deepLooks) deepLooks[index] else analysis?.best?.getOrNull(index)
        boardView.showSuggestion(if (replaying && moreToCome) engineMove else null)
        if (!show) return
        if (analysis == null) {
            reviewInfoText.text = reviewProgress ?: getString(R.string.review_running)
            reviewGraph.setData(emptyList(), emptyMap(), null, null)
            return
        }
        reviewGraph.setCurrent(index)
        reviewInfoText.text = reviewLine(analysis, index)
        if (moreToCome) scheduleDeepLook(index) else cancelDeepLook()
    }

    /**
     * The line for the position after [index] moves: the move played from it, named a mistake or
     * blunder only when the deeper search confirmed it, the engine's move when that was
     * different, and how the position went.
     */
    private fun reviewLine(analysis: GameController.Analysis, index: Int): String {
        val moves = gameController.getMoveHistory()
        if (index >= moves.size) {
            // A game reviewed before it is over ends on the position still being played.
            val end = getString(
                if (gameController.isGameOver()) R.string.review_end else R.string.review_now,
                analysis.evals.getOrNull(index)?.let { evalSummary(it) } ?: ""
            )
            return if (analysis.mistake == null && humanSide() != null) end + "\n" + getString(R.string.review_none) else end
        }
        val before = gameController.getInitialBoard().copy()
        for (i in 0 until index) before.makeMoveInPlace(moves[i])
        val move = moves[index]
        val side = getString(if (move.piece.color == PieceColor.RED) R.string.red_short else R.string.black_short)
        val deep = index in deepLooks
        val engineMove = if (deep) deepLooks[index] else analysis.best.getOrNull(index)
        val agrees = engineMove != null && engineMove.from == move.from && engineMove.to == move.to
        val tag = when (analysis.verdicts[index]) {
            Review.Verdict.BLUNDER -> getString(R.string.review_tag_blunder)
            Review.Verdict.MISTAKE -> getString(R.string.review_tag_mistake)
            else -> null
        }
        val headline = getString(R.string.review_move, index / 2 + 1, side, notation(move, before)) +
            (tag?.let { " · $it" } ?: "")
        val swing = getString(
            R.string.review_swing,
            analysis.evals.getOrNull(index)?.let { evalSummary(it) } ?: "?",
            analysis.evals.getOrNull(index + 1)?.let { evalSummary(it) } ?: "?"
        )
        val advice = when {
            engineMove == null -> null
            agrees -> getString(if (deep) R.string.review_same_deep else R.string.review_same)
            else -> getString(if (deep) R.string.review_better_deep else R.string.review_better, notation(engineMove, before))
        }
        val thinking = if (!deep && deepLookIndex == index) " · " + getString(R.string.review_deep_running) else ""
        return headline + "\n" + listOfNotNull(advice, swing).joinToString(" · ") + thinking
    }

    // ── Dialogs ──

    /** A game with moves on the board and no result yet. */
    private fun gameInProgress(): Boolean {
        return gameController.getMoveHistory().isNotEmpty() && !gameController.isGameOver()
    }

    /** Runs [action] at once, or after the player agrees to give up the game in progress. */
    private fun confirmAbandonThen(action: () -> Unit) {
        if (!gameInProgress()) {
            action()
            return
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.abandon_title)
            .setMessage(R.string.abandon_message)
            .setPositiveButton(R.string.abandon_confirm) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewGameDialog() {
        val modes = arrayOf(
            getString(R.string.play_red_vs_ai),
            getString(R.string.play_black_vs_ai),
            getString(R.string.two_players),
            getString(R.string.watch_ai),
            getString(R.string.endgame_practice)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.mode_select_title)
            .setAdapter(styledListAdapter(modes)) { _, which ->
                when (which) {
                    0 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.BLACK)
                        showDifficultyDialog()
                    }
                    1 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_AI, PieceColor.RED)
                        showDifficultyDialog()
                    }
                    2 -> {
                        gameController.setGameMode(GameMode.PLAYER_VS_PLAYER)
                        gameController.startNewGame()
                        updateGameModeDisplay()
                    }
                    3 -> {
                        gameController.setGameMode(GameMode.AI_VS_AI)
                        showDifficultyDialog()
                    }
                    4 -> showEndgameDialog()
                }
            }
            .show()
    }

    /** The studies by length of mate, each marked once solved, with the book it comes from. */
    private fun showEndgameDialog() {
        val studies = EndgameStudies.all(this)
        val solved = EndgameStudies.solved(this)
        val items = studies.map { study ->
            SpannableStringBuilder(
                getString(R.string.endgame_item, if (study.id in solved) "✓ " else "", study.mateIn, study.name)
            ).apply {
                if (study.source.isNotEmpty()) {
                    append("\n")
                    val start = length
                    append(getString(R.string.endgame_source, study.source))
                    setSpan(RelativeSizeSpan(0.8f), start, length, 0)
                    setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary)),
                        start, length, 0
                    )
                }
            }
        }.toTypedArray<CharSequence>()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(getString(R.string.endgame_select_progress, solved.count { id -> studies.any { it.id == id } }, studies.size))
            .setItems(items) { _, which -> startStudy(studies[which]) }
            .show()
    }

    private fun startStudy(study: EndgameStudy) {
        if (gameController.isInReplayMode()) exitReplay()
        gameController.startEndgame(study)
        updateGameModeDisplay()
    }

    /** Solved: next study or the same again. Not solved: again, or see how it goes. */
    private fun showEndgameResult(solved: Boolean) {
        val study = gameController.getEndgame() ?: return
        if (solved) EndgameStudies.markSolved(this, study.id)
        val next = EndgameStudies.nextUnsolved(this, study)
        val builder = AlertDialog.Builder(this, R.style.ChessDialogTheme)
        if (solved) {
            builder.setTitle(R.string.endgame_solved_title)
                .setMessage(getString(R.string.endgame_solved_message, study.name, study.mateIn))
            if (next != null) builder.setPositiveButton(R.string.endgame_next) { _, _ -> startStudy(next) }
            builder.setNegativeButton(R.string.endgame_retry) { _, _ -> startStudy(study) }
        } else {
            builder.setTitle(R.string.endgame_failed_title)
                .setMessage(getString(R.string.endgame_failed_message, study.mateIn))
                .setPositiveButton(R.string.endgame_retry) { _, _ -> startStudy(study) }
                .setNegativeButton(R.string.endgame_solution) { _, _ -> showSolution(study) }
            if (next != null) builder.setNeutralButton(R.string.endgame_next) { _, _ -> startStudy(next) }
        }
        builder.show()
    }

    private fun showSolution(study: EndgameStudy) {
        gameController.showSolution(study)
        updateGameModeDisplay()
        updateReplayBar()
        Snackbar.make(boardView, R.string.endgame_solution_note, Snackbar.LENGTH_LONG).show()
    }

    private fun updateReplayBar() {
        val replaying = gameController.isInReplayMode()
        replayBar.visibility = if (replaying) View.VISIBLE else View.GONE
        moveStrip.visibility = if (replaying) View.GONE else View.VISIBLE
        // A replayed position is only to look at: no picking up pieces, no stale selection.
        boardView.acceptsInput = !replaying
        if (replaying) {
            boardView.clearSelection()
            replayProgressText.text = getString(
                R.string.replay_counter, gameController.getReplayIndex(), gameController.getReplayLength()
            )
        }
        updateReviewPanel()
    }

    private fun exitReplay() {
        stopReview()
        boardView.showSuggestion(null)
        gameController.exitReplayMode()
        // Back on the live game, mark its last move again, not the one the replay stopped on.
        boardView.highlightMove(gameController.getMoveHistory().lastOrNull())
        updateGameModeDisplay()
        updateReplayBar()
    }

    /** English uses the WXF letters (C2=5); Chinese the four-character notation (炮二平五). */
    private val westernNotation by lazy { resources.getBoolean(R.bool.western_notation) }

    private fun notation(move: com.yingwang.chinesechess.model.Move, boardBefore: com.yingwang.chinesechess.model.Board): String =
        if (westernNotation) MoveNotation.formatWestern(move, boardBefore) else MoveNotation.format(move, boardBefore)

    /** The running evaluation is hidden unless the player asks for it: it gives too much away. */
    private val showEval: Boolean get() = settings.getBoolean(KEY_SHOW_EVAL, false)

    private fun applyEvalVisibility() {
        evalRow.visibility = if (showEval) View.VISIBLE else View.GONE
    }

    private fun toggleEval() {
        settings.edit().putBoolean(KEY_SHOW_EVAL, !showEval).apply()
        applyEvalVisibility()
    }

    private fun preferredDifficulty(): AIDifficulty? =
        settings.getString(KEY_DIFFICULTY, null)?.let { name -> AIDifficulty.values().firstOrNull { it.name == name } }

    /** Challenge games count towards the rating and allow no hints or take-backs; practice games are the opposite. */
    private val challengeMode: Boolean get() = settings.getBoolean(KEY_CHALLENGE, false)

    /** Whether hints and undo are withheld in the game on the board. */
    private fun isRatedGame(): Boolean =
        challengeMode && gameController.getGameMode() == GameMode.PLAYER_VS_AI && !gameController.isEndgameMode()

    /**
     * Picks a level (each with a line on who it suits) and practice or challenge, rebuilds the
     * controller with them and starts a fresh game in the current mode. On the first launch the
     * same dialog asks the player's level; leaving it keeps 初级.
     */
    private fun showDifficultyDialog(firstRun: Boolean = false) {
        val names = resources.getStringArray(R.array.difficulty_short)
        val notes = resources.getStringArray(R.array.difficulty_notes)
        val items = names.indices.map { i ->
            SpannableStringBuilder(names[i]).apply {
                append("\n")
                val start = length
                append(notes[i])
                setSpan(RelativeSizeSpan(0.8f), start, length, 0)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary)),
                    start, length, 0
                )
            }
        }.toTypedArray<CharSequence>()
        var chosen = (preferredDifficulty() ?: gameController.getDifficulty()).ordinal
        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        val dp = resources.displayMetrics.density
        val challengeSwitch = SwitchMaterial(this).apply {
            setText(R.string.challenge_mode)
            isChecked = challengeMode
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
            // The default off state is a dark thumb on a dark track, nearly invisible on
            // this panel; give both states colours that read on it.
            val checked = intArrayOf(android.R.attr.state_checked)
            val accent = ContextCompat.getColor(this@MainActivity, R.color.chess_accent)
            val secondary = ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary)
            thumbTintList = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(accent, secondary))
            trackTintList = ColorStateList(
                arrayOf(checked, intArrayOf()),
                intArrayOf(ColorUtils.setAlphaComponent(accent, 110), ColorUtils.setAlphaComponent(secondary, 90))
            )
        }
        val challengeNote = TextView(this).apply {
            setText(R.string.challenge_mode_note)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text_secondary))
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(challengeSwitch)
            addView(challengeNote)
        }

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(if (firstRun) R.string.difficulty_first_title else R.string.difficulty_title)
            .setSingleChoiceItems(items, chosen) { _, which -> chosen = which }
            .setView(footer)
            .setPositiveButton(R.string.ok) { _, _ ->
                val difficulty = AIDifficulty.values()[chosen]
                settings.edit()
                    .putString(KEY_DIFFICULTY, difficulty.name)
                    .putBoolean(KEY_CHALLENGE, challengeSwitch.isChecked)
                    .apply()

                gameController.destroy()
                gameController = GameController(this@MainActivity, difficulty, audioManager)
                setupGameControllerCallbacks()

                gameController.setGameMode(currentMode, currentAIColor)
                gameController.startNewGame()
                updateGameModeDisplay()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (firstRun) settings.edit().putString(KEY_DIFFICULTY, gameController.getDifficulty().name).apply()
            }
            .setOnCancelListener {
                if (firstRun) settings.edit().putString(KEY_DIFFICULTY, gameController.getDifficulty().name).apply()
            }
            .setOnDismissListener {
                // As with the resume question at launch: the first clock starts after it.
                if (firstRun) gameController.restartClockIfUnplayed()
            }
            .show()
    }

    private fun changeDifficulty() {
        if (gameController.getMoveHistory().isEmpty()) {
            showDifficultyDialog()
            return
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.difficulty_restart_title)
            .setMessage(R.string.difficulty_restart_message)
            .setPositiveButton(R.string.ok) { _, _ -> showDifficultyDialog() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun styledListAdapter(items: Array<String>): ListAdapter {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.chess_text))
                    textSize = 16f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return view
            }
        }
    }

    private fun showMoreDialog() {
        val items = arrayOf(
            getString(R.string.new_game),
            getString(if (isMuted) R.string.unmute else R.string.mute),
            getString(R.string.change_difficulty),
            getString(if (showEval) R.string.hide_eval else R.string.show_eval),
            getString(R.string.analysis_title),
            getString(R.string.replay_title),
            getString(R.string.export),
            getString(R.string.my_stats),
            getString(R.string.about),
            getString(R.string.theme_title)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.more)
            .setAdapter(styledListAdapter(items)) { _, which ->
                when (which) {
                    0 -> confirmAbandonThen { showNewGameDialog() }
                    1 -> toggleMute()
                    2 -> changeDifficulty()
                    3 -> toggleEval()
                    4 -> startReview()
                    5 -> toggleReplay()
                    6 -> exportMoveHistory()
                    7 -> showStatsDialog()
                    8 -> showAboutDialog()
                    9 -> showThemeDialog()
                }
            }
            .show()
    }

    private fun toggleReplay() {
        if (gameController.isInReplayMode()) {
            exitReplay()
            toast(R.string.replay_exited)
        } else if (gameController.enterReplayMode()) {
            updateGameModeDisplay()
            updateReplayBar()
        } else {
            toast(R.string.replay_none)
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        settings.edit().putBoolean("muted", isMuted).apply()
        audioManager.setMuted(isMuted)
        toast(if (isMuted) R.string.muted_toast else R.string.unmuted_toast)
    }

    private fun showStatsDialog() {
        val stats = RatingSystem.getStats(this)
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.my_stats)
            .setMessage(
                getString(
                    R.string.stats_message,
                    stats.rankTitle, stats.rating, stats.games,
                    stats.wins, stats.losses, stats.draws, stats.winRate
                )
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun exportMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            toast(R.string.export_none)
            return
        }

        val sb = StringBuilder()
        sb.appendLine(getString(R.string.export_title))
        sb.appendLine(getString(R.string.export_date, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())))
        sb.appendLine(getString(R.string.export_mode, modeDescription()))
        sb.appendLine(getString(R.string.export_moves, (moves.size + 1) / 2))
        sb.appendLine(getString(
            R.string.export_time,
            GameClock.format(gameController.clockTime(PieceColor.RED)),
            GameClock.format(gameController.clockTime(PieceColor.BLACK)),
            GameClock.format(gameController.totalClockTime())
        ))
        sb.appendLine("─".repeat(30))
        sb.appendLine()

        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard(), westernNotation)
        moves.forEachIndexed { index, _ ->
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                sb.append(String.format(Locale.US, "%2d. %-10s", moveNum, notations[index]))
            } else {
                sb.appendLine(String.format(Locale.US, "%-10s", notations[index]))
            }
        }
        if (moves.size % 2 == 1) sb.appendLine()

        sb.appendLine()
        sb.appendLine("─".repeat(30))

        val board = gameController.getCurrentBoard()
        val result = when {
            board.isCheckmate() -> getString(R.string.wins_short, sideName(board.currentPlayer.opposite()))
            board.isStalemate() -> getString(R.string.draw_short)
            else -> getString(R.string.result_unfinished)
        }
        sb.appendLine(getString(R.string.export_result, result))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_title))
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export)))
    }

    private fun showAboutDialog() {
        val verName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, verName, gameController.getAIStats()))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingAnimation()
        gameController.destroy()
        audioManager.release()
    }
}
