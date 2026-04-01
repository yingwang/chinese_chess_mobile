package com.yingwang.chinesechess

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yingwang.chinesechess.GameController.AIDifficulty
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.model.Piece
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: LinearLayout
    private lateinit var turnIndicatorDot: View
    private lateinit var thinkingDot1: View
    private lateinit var thinkingDot2: View
    private lateinit var thinkingDot3: View
    private lateinit var newGameButton: Button
    private lateinit var hintButton: Button
    private lateinit var undoButton: Button
    private lateinit var moreButton: Button
    private var isMuted = false
    private lateinit var gameController: GameController
    private lateinit var gameModeText: TextView
    private lateinit var redScoreText: TextView
    private lateinit var blackScoreText: TextView
    private lateinit var gameTimeText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var moveHistoryText: TextView
    private lateinit var blackCapturedLayout: LinearLayout
    private lateinit var redCapturedLayout: LinearLayout
    private lateinit var audioManager: GameAudioManager
    private var thinkingAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle edge-to-edge insets for Android 15+
        val rootView = findViewById<View>(android.R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        audioManager = GameAudioManager(this)
        gameController = GameController(this, AIDifficulty.PROFESSIONAL)
        initViews()
        setupGameController()
        gameController.startNewGame()
        startTimerUpdates()

        // Check for saved game and offer to resume
        if (gameController.hasSavedGame(this)) {
            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle("继续游戏")
                .setMessage("发现上次未完成的棋局，是否继续？")
                .setPositiveButton("继续") { _, _ ->
                    gameController.loadGame(this)
                    updateGameModeDisplay()
                }
                .setNegativeButton("新游戏") { _, _ ->
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
        // Auto-save game
        if (gameController.getMoveHistory().isNotEmpty()) {
            gameController.saveGame(this)
        }
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        turnIndicatorDot = findViewById(R.id.turnIndicatorDot)
        thinkingDot1 = findViewById(R.id.thinkingDot1)
        thinkingDot2 = findViewById(R.id.thinkingDot2)
        thinkingDot3 = findViewById(R.id.thinkingDot3)
        newGameButton = findViewById(R.id.newGameButton)
        hintButton = findViewById(R.id.hintButton)
        undoButton = findViewById(R.id.undoButton)
        gameModeText = findViewById(R.id.gameModeText)
        redScoreText = findViewById(R.id.redScoreText)
        blackScoreText = findViewById(R.id.blackScoreText)
        gameTimeText = findViewById(R.id.gameTimeText)
        moveCountText = findViewById(R.id.moveCountText)
        moveHistoryText = findViewById(R.id.moveHistoryText)
        blackCapturedLayout = findViewById(R.id.blackCapturedPieces)
        redCapturedLayout = findViewById(R.id.redCapturedPieces)
        moreButton = findViewById(R.id.moreButton)

        moreButton.setOnClickListener {
            showMoreDialog()
        }

        newGameButton.setOnClickListener {
            showNewGameDialog()
        }

        undoButton.setOnClickListener {
            if (gameController.getMoveHistory().isEmpty()) {
                Toast.makeText(this, "没有可以悔的棋", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this, R.style.ChessDialogTheme)
                .setTitle("确认悔棋")
                .setMessage("确定要悔棋吗？")
                .setPositiveButton("确定") { _, _ ->
                    if (gameController.undoLastMove()) {
                        boardView.clearSelection()
                        Toast.makeText(this, "已悔棋", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        hintButton.setOnClickListener {
            if (!gameController.isPlayerTurn()) {
                Toast.makeText(this, "不是你的回合", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            gameController.getHint { move ->
                runOnUiThread {
                    if (move != null) {
                        boardView.highlightMove(move)
                        AlertDialog.Builder(this, R.style.ChessDialogTheme)
                            .setTitle("提示")
                            .setMessage("建议走: ${formatMove(move)}")
                            .setPositiveButton("知道了", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this, R.style.ChessDialogTheme)
                            .setMessage("无法提供建议")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun setupGameController() {
        setupGameControllerCallbacks()
    }

    private fun setupGameControllerCallbacks() {
        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus(board.currentPlayer)
                if (gameController.getMoveHistory().isEmpty()) {
                    boardView.highlightMove(null)
                }
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread {
                val aiColor = gameController.getAIColor()
                val playerColor = aiColor.opposite()
                val isVsAI = gameController.getGameMode() == GameController.GameMode.PLAYER_VS_AI

                val (message, ratingMsg) = when (result) {
                    is GameController.GameResult.Checkmate -> {
                        val winner = if (result.winner == PieceColor.RED) "红方" else "黑方"
                        val baseMsg = "$winner 获胜！"
                        if (isVsAI) {
                            val score = if (result.winner == playerColor) 1.0 else 0.0
                            val change = RatingSystem.recordGame(this, gameController.getDifficulty(), score)
                            val stats = RatingSystem.getStats(this)
                            val sign = if (change >= 0) "+" else ""
                            baseMsg to "\n\n积分: ${stats.rating} ($sign$change)\n等级: ${stats.rankTitle}"
                        } else baseMsg to ""
                    }
                    GameController.GameResult.Stalemate -> {
                        val baseMsg = "和棋！"
                        if (isVsAI) {
                            val change = RatingSystem.recordGame(this, gameController.getDifficulty(), 0.5)
                            val stats = RatingSystem.getStats(this)
                            val sign = if (change >= 0) "+" else ""
                            baseMsg to "\n\n积分: ${stats.rating} ($sign$change)\n等级: ${stats.rankTitle}"
                        } else baseMsg to ""
                    }
                }

                AlertDialog.Builder(this, R.style.ChessDialogTheme)
                    .setTitle("游戏结束")
                    .setMessage(message + ratingMsg)
                    .setPositiveButton("新游戏") { _, _ -> gameController.startNewGame() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        gameController.onAIThinking = { isThinking ->
            runOnUiThread {
                aiThinkingIndicator.visibility = if (isThinking) View.VISIBLE else View.GONE
                if (isThinking) startThinkingAnimation() else stopThinkingAnimation()
                undoButton.isEnabled = !isThinking
                hintButton.isEnabled = !isThinking
                statusText.text = if (isThinking) "AI思考中..." else getStatusText()
            }
        }

        gameController.onMoveCompleted = { move ->
            runOnUiThread {
                boardView.highlightMove(move)
                // Haptic feedback
                boardView.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                // Sound effects
                if (move.capturedPiece != null) {
                    audioManager.playCaptureSound()
                } else {
                    audioManager.playMoveSound()
                }
            }
        }

        gameController.onStatsUpdated = { stats ->
            runOnUiThread {
                updateGameStats(stats)
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
                Toast.makeText(this, "回放模式中，不能走棋", Toast.LENGTH_SHORT).show()
                boardView.clearSelection()
                return@setOnMoveListener
            }
            if (!gameController.isPlayerTurn()) {
                Toast.makeText(this, "不是你的回合", Toast.LENGTH_SHORT).show()
                boardView.clearSelection()
                return@setOnMoveListener
            }

            if (gameController.makePlayerMove(move)) {
                boardView.clearSelection()
            } else {
                Toast.makeText(this, "非法移动", Toast.LENGTH_SHORT).show()
            }
        }

        updateGameModeDisplay()
    }

    private fun updateStatus(currentPlayer: PieceColor) {
        statusText.text = getStatusText()
        // Update turn indicator dot color
        val dotDrawable = turnIndicatorDot.background as? GradientDrawable
        dotDrawable?.setColor(
            if (currentPlayer == PieceColor.RED) Color.rgb(200, 40, 40)
            else Color.rgb(40, 40, 40)
        )
    }

    private fun getStatusText(): String {
        val board = gameController.getCurrentBoard()
        val playerName = if (board.currentPlayer == PieceColor.RED) "红方" else "黑方"

        return when {
            board.isCheckmate() -> {
                val winner = if (board.currentPlayer == PieceColor.RED) "黑方" else "红方"
                "$winner 获胜！"
            }
            board.isStalemate() -> "和棋"
            board.isInCheck(board.currentPlayer) -> "$playerName 将军！"
            else -> "$playerName 走棋"
        }
    }

    private fun updateGameStats(stats: GameController.GameStats) {
        redScoreText.text = "红方: ${stats.redScore}"
        blackScoreText.text = "黑方: ${stats.blackScore}"
        gameTimeText.text = formatTime(stats.gameTime)
        moveCountText.text = "回合: ${stats.moveNumber}"
        updateMoveHistory()
        updateCapturedPiecesDisplay(stats)
    }

    private fun updateCapturedPiecesDisplay(stats: GameController.GameStats) {
        updateCapturedRow(blackCapturedLayout, stats.redCapturedPieces)
        updateCapturedRow(redCapturedLayout, stats.blackCapturedPieces)
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
                    if (piece.color == PieceColor.RED) Color.rgb(170, 20, 20)
                    else Color.rgb(40, 40, 40)
                )
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (2 * dp).toInt()
                }
                // Mini piece circle background
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.rgb(235, 220, 195))
                    setStroke((1 * dp).toInt(), Color.rgb(140, 110, 70))
                }
                background = bg
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
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            moveHistoryText.text = "棋谱:"
            return
        }

        val history = StringBuilder("棋谱:\n")
        moves.forEachIndexed { index, move ->
            val moveNum = index / 2 + 1
            val formattedMove = formatMove(move)

            if (index % 2 == 0) {
                history.append(String.format("%2d. %-8s", moveNum, formattedMove))
            } else {
                history.append(String.format("%-8s\n", formattedMove))
            }
        }

        if (moves.size % 2 == 1) {
            history.append("\n")
        }

        moveHistoryText.text = history.toString()

        moveHistoryText.post {
            val scrollView = moveHistoryText.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun formatMove(move: com.yingwang.chinesechess.model.Move): String {
        val isRed = move.piece.color == PieceColor.RED
        val pieceChar = move.piece.type.getDisplayName(move.piece.color)

        val columnNotation = if (isRed) {
            arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")[move.from.col]
        } else {
            (move.from.col + 1).toString()
        }

        val rowDiff = move.to.row - move.from.row
        val colDiff = move.to.col - move.from.col

        val (direction, steps) = when {
            rowDiff == 0 -> {
                val destCol = if (isRed) {
                    arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")[move.to.col]
                } else {
                    (move.to.col + 1).toString()
                }
                "平" to destCol
            }
            (isRed && rowDiff < 0) || (!isRed && rowDiff > 0) -> {
                val stepCount = Math.abs(rowDiff)
                val stepNotation = if (isRed) {
                    arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[stepCount]
                } else {
                    stepCount.toString()
                }
                "进" to stepNotation
            }
            else -> {
                val stepCount = Math.abs(rowDiff)
                val stepNotation = if (isRed) {
                    arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[stepCount]
                } else {
                    stepCount.toString()
                }
                "退" to stepNotation
            }
        }

        return "$pieceChar$columnNotation$direction$steps"
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

    private fun updateGameModeDisplay() {
        val modeText = when {
            gameController.isInReplayMode() -> "棋谱回放"
            gameController.isEndgameMode() -> "残局练习"
            else -> when (gameController.getGameMode()) {
                GameController.GameMode.PLAYER_VS_PLAYER -> "玩家 vs 玩家"
                GameController.GameMode.PLAYER_VS_AI -> {
                    val playerColor = if (gameController.getAIColor() == PieceColor.RED) "黑" else "红"
                    "玩家(${playerColor}) vs AI"
                }
                GameController.GameMode.AI_VS_AI -> "AI vs AI"
            }
        }
        gameModeText.text = modeText
    }

    private fun showNewGameDialog() {
        val modes = arrayOf(
            "我执红先手 vs AI",
            "我执黑后手 vs AI",
            "双人对战",
            "AI vs AI (观战)",
            "残局练习"
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("选择游戏模式")
            .setAdapter(styledListAdapter(modes)) { _, which ->
                when (which) {
                    0 -> {
                        gameController.setGameMode(GameController.GameMode.PLAYER_VS_AI, PieceColor.BLACK)
                        showDifficultyDialog()
                    }
                    1 -> {
                        gameController.setGameMode(GameController.GameMode.PLAYER_VS_AI, PieceColor.RED)
                        showDifficultyDialog()
                    }
                    2 -> {
                        gameController.setGameMode(GameController.GameMode.PLAYER_VS_PLAYER)
                        gameController.startNewGame()
                        updateGameModeDisplay()
                    }
                    3 -> {
                        gameController.setGameMode(GameController.GameMode.AI_VS_AI)
                        showDifficultyDialog()
                    }
                    4 -> {
                        showEndgameDialog()
                    }
                }
            }
            .show()
    }

    private fun showEndgameDialog() {
        val names = EndgamePositions.positions.map { "${it.name} - ${it.description}" }.toTypedArray()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("选择残局")
            .setAdapter(styledListAdapter(names)) { _, which ->
                val position = EndgamePositions.positions[which]
                gameController.startEndgamePosition(position)
                updateGameModeDisplay()
            }
            .show()
    }

    private fun showReplayControls() {
        val dialog = AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("棋谱回放")
            .setMessage(gameController.getReplayInfo())
            .setPositiveButton("下一步") { _, _ -> }
            .setNegativeButton("上一步") { _, _ -> }
            .setNeutralButton("退出回放") { _, _ ->
                gameController.exitReplayMode()
                updateGameModeDisplay()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        // Override button behaviors to prevent auto-dismiss
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (gameController.replayStepForward()) {
                dialog.setMessage(gameController.getReplayInfo())
            } else {
                Toast.makeText(this, "已到最后一步", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (gameController.replayStepBack()) {
                dialog.setMessage(gameController.getReplayInfo())
            } else {
                Toast.makeText(this, "已到第一步", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDifficultyDialog() {
        val difficulties = arrayOf(
            "初级 (Beginner)",
            "中级 (Intermediate)",
            "高级 (Advanced)",
            "专业 (Professional)",
            "大师 (Master)",
            "AI 神经网络 (ML)"
        )

        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("选择AI难度")
            .setAdapter(styledListAdapter(difficulties)) { _, which ->
                val difficulty = when (which) {
                    0 -> AIDifficulty.BEGINNER
                    1 -> AIDifficulty.INTERMEDIATE
                    2 -> AIDifficulty.ADVANCED
                    3 -> AIDifficulty.PROFESSIONAL
                    4 -> AIDifficulty.MASTER
                    5 -> AIDifficulty.ML
                    else -> AIDifficulty.PROFESSIONAL
                }

                gameController.destroy()
                gameController = GameController(this@MainActivity, difficulty)
                setupGameController()

                gameController.setGameMode(currentMode, currentAIColor)
                gameController.startNewGame()
                updateGameModeDisplay()
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_new_game -> {
                showNewGameDialog()
                true
            }
            R.id.action_undo -> {
                gameController.undoLastMove()
                true
            }
            R.id.action_endgame -> {
                showEndgameDialog()
                true
            }
            R.id.action_replay -> {
                if (gameController.isInReplayMode()) {
                    gameController.exitReplayMode()
                    updateGameModeDisplay()
                    Toast.makeText(this, "退出回放模式", Toast.LENGTH_SHORT).show()
                } else if (gameController.enterReplayMode()) {
                    updateGameModeDisplay()
                    showReplayControls()
                } else {
                    Toast.makeText(this, "没有可回放的棋谱", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_mute -> {
                isMuted = !isMuted
                gameController.setSoundEnabled(!isMuted)
                audioManager.setMuted(isMuted)
                if (isMuted) audioManager.pauseBackgroundMusic()
                else audioManager.startBackgroundMusic()
                Toast.makeText(this, if (isMuted) "已静音" else "已开启音效", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_stats -> {
                showStatsDialog()
                true
            }
            R.id.action_export -> {
                exportMoveHistory()
                true
            }
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun styledListAdapter(items: Array<String>): android.widget.ListAdapter {
        return object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.rgb(240, 224, 192))
                    textSize = 16f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    setBackgroundColor(Color.TRANSPARENT)
                }
                return view
            }
        }
    }

    private fun showMoreDialog() {
        val muteLabel = if (isMuted) "取消静音" else "静音"
        val items = arrayOf(
            muteLabel,
            "导出棋谱",
            "我的战绩",
            "残局练习",
            "棋谱回放",
            "关于"
        )

        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("更多")
            .setAdapter(styledListAdapter(items)) { _, which ->
                when (which) {
                    0 -> { // 静音
                        isMuted = !isMuted
                        gameController.setSoundEnabled(!isMuted)
                        audioManager.setMuted(isMuted)
                        if (isMuted) audioManager.pauseBackgroundMusic()
                        else audioManager.startBackgroundMusic()
                        Toast.makeText(this, if (isMuted) "已静音" else "已开启音效", Toast.LENGTH_SHORT).show()
                    }
                    1 -> exportMoveHistory()
                    2 -> showStatsDialog()
                    3 -> showEndgameDialog()
                    4 -> { // 棋谱回放
                        if (gameController.isInReplayMode()) {
                            gameController.exitReplayMode()
                            updateGameModeDisplay()
                            Toast.makeText(this, "退出回放模式", Toast.LENGTH_SHORT).show()
                        } else if (gameController.enterReplayMode()) {
                            updateGameModeDisplay()
                            showReplayControls()
                        } else {
                            Toast.makeText(this, "没有可回放的棋谱", Toast.LENGTH_SHORT).show()
                        }
                    }
                    5 -> showAboutDialog()
                }
            }
            .show()
    }

    private fun showStatsDialog() {
        val stats = RatingSystem.getStats(this)
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("我的战绩")
            .setMessage("""
                等级: ${stats.rankTitle}
                积分: ${stats.rating}

                总局数: ${stats.games}
                胜: ${stats.wins}  负: ${stats.losses}  平: ${stats.draws}
                胜率: ${stats.winRate}
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun exportMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            Toast.makeText(this, "没有棋谱可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.appendLine("中国象棋棋谱")
        sb.appendLine("日期: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("模式: ${gameModeText.text}")
        sb.appendLine("回合数: ${moves.size}")
        sb.appendLine("─".repeat(30))
        sb.appendLine()

        moves.forEachIndexed { index, move ->
            val moveNum = index / 2 + 1
            val formatted = formatMove(move)
            if (index % 2 == 0) {
                sb.append(String.format("%2d. %-10s", moveNum, formatted))
            } else {
                sb.appendLine(String.format("%-10s", formatted))
            }
        }
        if (moves.size % 2 == 1) sb.appendLine()

        sb.appendLine()
        sb.appendLine("─".repeat(30))

        val board = gameController.getCurrentBoard()
        val result = when {
            board.isCheckmate() -> {
                val winner = if (board.currentPlayer == PieceColor.RED) "黑方" else "红方"
                "$winner 获胜"
            }
            board.isStalemate() -> "和棋"
            else -> "未结束"
        }
        sb.appendLine("结果: $result")

        val text = sb.toString()

        // Share via Android share sheet
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "中国象棋棋谱")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "导出棋谱"))
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this, R.style.ChessDialogTheme)
            .setTitle("关于中国象棋")
            .setMessage("""
                中国象棋 v1.0

                专业级AI引擎特性:
                • Alpha-Beta剪枝
                • 置换表缓存
                • 迭代加深搜索
                • 静态搜索
                • 移动排序优化

                ${gameController.getAIStats()}
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopThinkingAnimation()
        gameController.destroy()
        audioManager.release()
    }
}
