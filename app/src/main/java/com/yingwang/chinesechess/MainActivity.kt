package com.yingwang.chinesechess

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.yingwang.chinesechess.GameController.AIDifficulty
import com.yingwang.chinesechess.GameController.GameMode
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.model.Piece
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView
import com.yingwang.chinesechess.ui.EvalBarView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Mates farther away than this are shown as a plain "winning"/"losing". */
        private const val MATE_HINT_LIMIT = 3
    }

    private lateinit var boardView: BoardView

    // Header
    private lateinit var redCard: View
    private lateinit var blackCard: View
    private lateinit var redTurnDot: View
    private lateinit var blackTurnDot: View
    private lateinit var redRoleText: TextView
    private lateinit var blackRoleText: TextView
    private lateinit var redScoreText: TextView
    private lateinit var blackScoreText: TextView
    private lateinit var redCapturedLayout: LinearLayout
    private lateinit var blackCapturedLayout: LinearLayout
    private lateinit var gameTimeText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var gameModeText: TextView
    private lateinit var evalBar: EvalBarView
    private var evaluation: GameController.Evaluation? = null
    private var lastStats: GameController.GameStats? = null

    // Status pill
    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: LinearLayout
    private lateinit var thinkingDot1: View
    private lateinit var thinkingDot2: View
    private lateinit var thinkingDot3: View

    private lateinit var moveHistoryText: TextView
    private lateinit var newGameButton: Button
    private lateinit var hintButton: Button
    private lateinit var undoButton: Button
    private lateinit var moreButton: Button

    private var isMuted = false
    private val settings by lazy { getSharedPreferences("chess_settings", MODE_PRIVATE) }
    private lateinit var audioManager: GameAudioManager
    private lateinit var gameController: GameController
    private var thinkingAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The ground colour comes from the palette, not from day/night mode, so the bar
        // icons follow a palette flag rather than the system setting.
        if (resources.getBoolean(R.bool.chess_light_system_bars)) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        }
        super.onCreate(savedInstanceState)
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
        // A saved game remembers its difficulty; start the controller with it so
        // resuming faces the same opponent instead of always PROFESSIONAL.
        val startDifficulty = GameController.savedDifficulty(this) ?: AIDifficulty.PROFESSIONAL
        gameController = GameController(this, startDifficulty, audioManager)
        initViews()
        setupGameControllerCallbacks()
        gameController.startNewGame()
        startTimerUpdates()

        if (gameController.hasSavedGame(this)) {
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle(R.string.resume_title)
                .setMessage(R.string.resume_message)
                .setPositiveButton(R.string.resume_continue) { _, _ ->
                    gameController.loadGame(this)
                    updateGameModeDisplay()
                }
                .setNegativeButton(R.string.new_game) { _, _ ->
                    gameController.deleteSavedGame(this)
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isMuted) audioManager.startBackgroundMusic()
    }

    override fun onPause() {
        super.onPause()
        audioManager.pauseBackgroundMusic()
        if (gameController.getMoveHistory().isNotEmpty()) {
            gameController.saveGame(this)
        }
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        redCard = findViewById(R.id.redCard)
        blackCard = findViewById(R.id.blackCard)
        redTurnDot = findViewById(R.id.redTurnDot)
        blackTurnDot = findViewById(R.id.blackTurnDot)
        redRoleText = findViewById(R.id.redRoleText)
        blackRoleText = findViewById(R.id.blackRoleText)
        redScoreText = findViewById(R.id.redScoreText)
        blackScoreText = findViewById(R.id.blackScoreText)
        redCapturedLayout = findViewById(R.id.redCapturedPieces)
        blackCapturedLayout = findViewById(R.id.blackCapturedPieces)
        gameTimeText = findViewById(R.id.gameTimeText)
        moveCountText = findViewById(R.id.moveCountText)
        gameModeText = findViewById(R.id.gameModeText)
        evalBar = findViewById(R.id.evalBar)
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        thinkingDot1 = findViewById(R.id.thinkingDot1)
        thinkingDot2 = findViewById(R.id.thinkingDot2)
        thinkingDot3 = findViewById(R.id.thinkingDot3)
        moveHistoryText = findViewById(R.id.moveHistoryText)
        newGameButton = findViewById(R.id.newGameButton)
        hintButton = findViewById(R.id.hintButton)
        undoButton = findViewById(R.id.undoButton)
        moreButton = findViewById(R.id.moreButton)

        newGameButton.setOnClickListener { showNewGameDialog() }
        moreButton.setOnClickListener { showMoreDialog() }

        undoButton.setOnClickListener {
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
                        toast(R.string.undo_done)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        hintButton.setOnClickListener {
            if (!gameController.isPlayerTurn()) {
                toast(R.string.not_your_turn)
                return@setOnClickListener
            }
            gameController.getHint { move ->
                runOnUiThread {
                    if (move != null) {
                        boardView.highlightMove(move)
                        val text = MoveNotation.format(move, gameController.getCurrentBoard())
                        Snackbar.make(boardView, getString(R.string.hint_suggest, text), 4000).show()
                    } else {
                        Snackbar.make(boardView, R.string.hint_unavailable, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }

        updateScoreLines()
        moveCountText.text = getString(R.string.round_label, 0)
    }

    private fun setupGameControllerCallbacks() {
        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus()
                if (gameController.getMoveHistory().isEmpty()) {
                    boardView.highlightMove(null)
                }
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread {
                val playerColor = gameController.getAIColor().opposite()
                val isVsAI = gameController.getGameMode() == GameMode.PLAYER_VS_AI

                fun rated(score: Double): String {
                    if (!isVsAI) return ""
                    val change = RatingSystem.recordGame(this, gameController.getDifficulty(), score)
                    val stats = RatingSystem.getStats(this)
                    val sign = if (change >= 0) "+" else ""
                    return getString(R.string.rating_summary, stats.rating.toString(), "$sign$change", stats.rankTitle)
                }

                val message = when (result) {
                    is GameController.GameResult.Checkmate ->
                        getString(R.string.wins, sideName(result.winner)) +
                            rated(if (result.winner == playerColor) 1.0 else 0.0)
                    is GameController.GameResult.PerpetualCheck ->
                        getString(R.string.perpetual_check_loss, sideName(result.winner)) +
                            rated(if (result.winner == playerColor) 1.0 else 0.0)
                    GameController.GameResult.Stalemate ->
                        getString(R.string.draw) + rated(0.5)
                    GameController.GameResult.RepetitionDraw ->
                        getString(R.string.repetition_draw) + rated(0.5)
                }

                AlertDialog.Builder(this, R.style.ChessDialogTheme)
                    .setTitle(R.string.game_over)
                    .setMessage(message)
                    .setPositiveButton(R.string.new_game) { _, _ -> gameController.startNewGame() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        gameController.onAIThinking = { isThinking ->
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
    }

    private fun getStatusText(): String {
        val board = gameController.getCurrentBoard()
        val side = sideName(board.currentPlayer)
        return when {
            board.isCheckmate() -> getString(R.string.wins, sideName(board.currentPlayer.opposite()))
            board.isStalemate() -> getString(R.string.draw_short)
            board.isInCheck(board.currentPlayer) -> getString(R.string.in_check, side)
            else -> getString(R.string.side_to_move, side)
        }
    }

    /**
     * Each card shows the engine's view of the game from its own side. Without an engine
     * (fallback search) the old material count stands in.
     */
    private fun updateScoreLines() {
        val eval = evaluation
        if (eval == null) {
            redScoreText.text = getString(R.string.score_label, lastStats?.redScore ?: 0)
            blackScoreText.text = getString(R.string.score_label, lastStats?.blackScore ?: 0)
            return
        }
        redScoreText.text = evalText(eval, PieceColor.RED)
        blackScoreText.text = evalText(eval, PieceColor.BLACK)
    }

    private fun evalText(eval: GameController.Evaluation, side: PieceColor): String {
        val sign = if (side == PieceColor.RED) 1 else -1
        eval.mateRed?.let { mate ->
            // A mate count is a spoiler, so only a short one is spelled out.
            val mine = mate * sign
            return when {
                mine > 0 && mine <= MATE_HINT_LIMIT -> getString(R.string.eval_mate_win, mine)
                mine < 0 && -mine <= MATE_HINT_LIMIT -> getString(R.string.eval_mate_loss, -mine)
                mine > 0 -> getString(R.string.eval_winning)
                else -> getString(R.string.eval_losing)
            }
        }
        val pawns = ((eval.cpRed ?: 0) * sign) / 100.0
        return getString(R.string.eval_label, String.format(Locale.US, "%+.1f", pawns))
    }

    private fun updateGameStats(stats: GameController.GameStats) {
        lastStats = stats
        updateScoreLines()
        gameTimeText.text = formatTime(stats.gameTime)
        moveCountText.text = getString(R.string.round_label, stats.moveNumber)
        updateMoveHistory()
        // Each card shows the pieces its side has taken.
        updateCapturedRow(redCapturedLayout, stats.redCapturedPieces)
        updateCapturedRow(blackCapturedLayout, stats.blackCapturedPieces)
    }

    private fun updateCapturedRow(container: LinearLayout, pieces: List<Piece>) {
        container.removeAllViews()
        val sorted = pieces.sortedByDescending { it.type.baseValue }
        val dp = resources.displayMetrics.density
        val size = (22 * dp).toInt()

        for (piece in sorted) {
            val tv = TextView(this).apply {
                text = piece.type.getDisplayName(piece.color)
                textSize = 11f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (piece.color == PieceColor.RED) R.color.chess_piece_red_ink else R.color.chess_piece_black_ink
                    )
                )
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (2 * dp).toInt()
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.chess_captured_bg))
                    setStroke((1 * dp).toInt(), ContextCompat.getColor(this@MainActivity, R.color.chess_captured_stroke))
                }
            }
            container.addView(tv)
        }
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

    private fun formatTime(timeInMillis: Long): String {
        val totalSeconds = timeInMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            moveHistoryText.text = getString(R.string.history_empty)
            return
        }

        val history = StringBuilder()
        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard())
        moves.forEachIndexed { index, _ ->
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                history.append(String.format(Locale.US, "%2d. %s", moveNum, notations[index]))
            } else {
                history.append("    ").append(notations[index]).append('\n')
            }
        }
        if (moves.size % 2 == 1) history.append('\n')

        moveHistoryText.text = history.toString()
        moveHistoryText.post {
            (moveHistoryText.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun startTimerUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val gameTime = System.currentTimeMillis() - gameController.getGameStartTime()
                gameTimeText.text = formatTime(gameTime)
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
            GameMode.PLAYER_VS_AI -> getString(R.string.mode_pvai, difficultyShortName())
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
        updateStatus()
    }

    // ── Dialogs ──

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

    private fun showEndgameDialog() {
        val names = EndgamePositions.positions
            .map { getString(R.string.endgame_item, it.name, it.description) }
            .toTypedArray()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.endgame_select_title)
            .setAdapter(styledListAdapter(names)) { _, which ->
                gameController.startEndgamePosition(EndgamePositions.positions[which])
                updateGameModeDisplay()
            }
            .show()
    }

    private fun showReplayControls() {
        val dialog = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.replay_title)
            .setMessage(gameController.getReplayInfo())
            .setPositiveButton(R.string.replay_next) { _, _ -> }
            .setNegativeButton(R.string.replay_prev) { _, _ -> }
            .setNeutralButton(R.string.replay_exit) { _, _ ->
                gameController.exitReplayMode()
                updateGameModeDisplay()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        // Override button behaviours to prevent auto-dismiss
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (gameController.replayStepForward()) {
                dialog.setMessage(gameController.getReplayInfo())
            } else {
                toast(R.string.replay_at_end)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (gameController.replayStepBack()) {
                dialog.setMessage(gameController.getReplayInfo())
            } else {
                toast(R.string.replay_at_start)
            }
        }
    }

    /** Picks a difficulty, rebuilds the controller with it and starts a fresh game in the current mode. */
    private fun showDifficultyDialog() {
        val difficulties = resources.getStringArray(R.array.difficulty_names)
        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.difficulty_title)
            .setAdapter(styledListAdapter(difficulties)) { _, which ->
                val difficulty = AIDifficulty.values().getOrElse(which) { AIDifficulty.PROFESSIONAL }

                gameController.destroy()
                gameController = GameController(this@MainActivity, difficulty, audioManager)
                setupGameControllerCallbacks()

                gameController.setGameMode(currentMode, currentAIColor)
                gameController.startNewGame()
                updateGameModeDisplay()
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
            getString(if (isMuted) R.string.unmute else R.string.mute),
            getString(R.string.change_difficulty),
            getString(R.string.export),
            getString(R.string.my_stats),
            getString(R.string.replay_title),
            getString(R.string.about)
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle(R.string.more)
            .setAdapter(styledListAdapter(items)) { _, which ->
                when (which) {
                    0 -> toggleMute()
                    1 -> changeDifficulty()
                    2 -> exportMoveHistory()
                    3 -> showStatsDialog()
                    4 -> toggleReplay()
                    5 -> showAboutDialog()
                }
            }
            .show()
    }

    private fun toggleReplay() {
        if (gameController.isInReplayMode()) {
            gameController.exitReplayMode()
            updateGameModeDisplay()
            toast(R.string.replay_exited)
        } else if (gameController.enterReplayMode()) {
            updateGameModeDisplay()
            showReplayControls()
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
        sb.appendLine(getString(R.string.export_moves, moves.size))
        sb.appendLine("─".repeat(30))
        sb.appendLine()

        val notations = MoveNotation.formatAll(moves, gameController.getInitialBoard())
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
