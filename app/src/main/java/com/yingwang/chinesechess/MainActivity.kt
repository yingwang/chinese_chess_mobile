package com.yingwang.chinesechess

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yingwang.chinesechess.audio.GameAudioManager
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var boardView: BoardView
    private lateinit var statusText: TextView
    private lateinit var aiThinkingIndicator: ProgressBar
    private lateinit var newGameButton: Button
    private lateinit var undoButton: Button
    private lateinit var gameController: GameController
    private lateinit var gameModeText: TextView
    private lateinit var redScoreText: TextView
    private lateinit var blackScoreText: TextView
    private lateinit var gameTimeText: TextView
    private lateinit var moveCountText: TextView
    private lateinit var moveHistoryText: TextView
    private lateinit var audioManager: GameAudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = GameAudioManager(this)
        initViews()
        setupGameController()
        gameController.startNewGame()
        startTimerUpdates()
    }

    override fun onResume() {
        super.onResume()
        audioManager.startBackgroundMusic()
    }

    override fun onPause() {
        super.onPause()
        audioManager.pauseBackgroundMusic()
    }

    private fun initViews() {
        boardView = findViewById(R.id.boardView)
        statusText = findViewById(R.id.statusText)
        aiThinkingIndicator = findViewById(R.id.aiThinkingIndicator)
        newGameButton = findViewById(R.id.newGameButton)
        undoButton = findViewById(R.id.undoButton)
        gameModeText = findViewById(R.id.gameModeText)
        redScoreText = findViewById(R.id.redScoreText)
        blackScoreText = findViewById(R.id.blackScoreText)
        gameTimeText = findViewById(R.id.gameTimeText)
        moveCountText = findViewById(R.id.moveCountText)
        moveHistoryText = findViewById(R.id.moveHistoryText)

        newGameButton.setOnClickListener {
            showNewGameDialog()
        }

        undoButton.setOnClickListener {
            if (gameController.undoLastMove()) {
                boardView.clearSelection()
                Toast.makeText(this, "Move undone", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No moves to undo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupGameController() {
        gameController = GameController(GameController.AIDifficulty.PROFESSIONAL)

        gameController.onBoardUpdated = { board ->
            runOnUiThread {
                boardView.setBoard(board)
                updateStatus(board.currentPlayer)
            }
        }

        gameController.onGameOver = { result ->
            runOnUiThread {
                val message = when (result) {
                    is GameController.GameResult.Checkmate -> {
                        val winner = if (result.winner == PieceColor.RED) "红方" else "黑方"
                        "$winner 获胜！"
                    }
                    GameController.GameResult.Stalemate -> "和棋！"
                }

                AlertDialog.Builder(this)
                    .setTitle("游戏结束")
                    .setMessage(message)
                    .setPositiveButton("新游戏") { _, _ -> gameController.startNewGame() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        gameController.onAIThinking = { isThinking ->
            runOnUiThread {
                aiThinkingIndicator.visibility = if (isThinking) View.VISIBLE else View.GONE
                undoButton.isEnabled = !isThinking
                statusText.text = if (isThinking) "AI思考中..." else getStatusText()
            }
        }

        gameController.onMoveCompleted = { move ->
            runOnUiThread {
                boardView.highlightMove(move)
                // Play appropriate sound effect
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

        boardView.setOnMoveListener { move ->
            // Check if it's the player's turn
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
        // Score represents material advantage (captured piece values)
        redScoreText.text = "红方: ${stats.redScore}"
        blackScoreText.text = "黑方: ${stats.blackScore}"

        gameTimeText.text = formatTime(stats.gameTime)

        // Update move count
        moveCountText.text = "回合: ${stats.moveNumber}"

        // Update move history
        updateMoveHistory()
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
                // Red's move (start of new round)
                history.append(String.format("%2d. %-8s", moveNum, formattedMove))
            } else {
                // Black's move (end of round)
                history.append(String.format("%-8s\n", formattedMove))
            }
        }

        // If last move was red's move (odd number of moves), add newline
        if (moves.size % 2 == 1) {
            history.append("\n")
        }

        moveHistoryText.text = history.toString()

        // Auto-scroll to bottom
        moveHistoryText.post {
            val scrollView = moveHistoryText.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun formatMove(move: com.yingwang.chinesechess.model.Move): String {
        // Format move in standard Chinese chess notation (e.g., "车五进九")
        val isRed = move.piece.color == PieceColor.RED

        // Get piece display name based on color
        val pieceChar = move.piece.type.getDisplayName(move.piece.color)

        // Get column position from player's perspective
        // Red: columns 0-8 map to 九八七六五四三二一 (right to left)
        // Black: columns 0-8 map to 1-9 (left to right)
        val columnNotation = if (isRed) {
            arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")[move.from.col]
        } else {
            (move.from.col + 1).toString()
        }

        // Determine direction and steps
        val rowDiff = move.to.row - move.from.row
        val colDiff = move.to.col - move.from.col

        val (direction, steps) = when {
            rowDiff == 0 -> {
                // Horizontal move (平)
                val destCol = if (isRed) {
                    arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")[move.to.col]
                } else {
                    (move.to.col + 1).toString()
                }
                "平" to destCol
            }
            (isRed && rowDiff < 0) || (!isRed && rowDiff > 0) -> {
                // Forward move (进)
                val stepCount = Math.abs(rowDiff)
                val stepNotation = if (isRed) {
                    arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[stepCount]
                } else {
                    stepCount.toString()
                }
                "进" to stepNotation
            }
            else -> {
                // Backward move (退)
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
                delay(1000) // Update every second
                val gameTime = System.currentTimeMillis() - gameController.getGameStartTime()
                gameTimeText.text = formatTime(gameTime)
            }
        }
    }

    private fun updateGameModeDisplay() {
        val modeText = when (gameController.getGameMode()) {
            GameController.GameMode.PLAYER_VS_PLAYER -> "玩家 vs 玩家"
            GameController.GameMode.PLAYER_VS_AI -> {
                val playerColor = if (gameController.getAIColor() == PieceColor.RED) "黑" else "红"
                "玩家(${playerColor}) vs AI"
            }
            GameController.GameMode.AI_VS_AI -> "AI vs AI"
        }
        gameModeText.text = modeText
    }

    private fun showNewGameDialog() {
        val modes = arrayOf(
            "玩家 vs AI (红方)",
            "玩家 vs AI (黑方)",
            "玩家 vs 玩家",
            "AI vs AI"
        )

        AlertDialog.Builder(this)
            .setTitle("选择游戏模式")
            .setItems(modes) { _, which ->
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
                }
            }
            .show()
    }

    private fun showDifficultyDialog() {
        val difficulties = arrayOf(
            "初级 (Beginner)",
            "中级 (Intermediate)",
            "高级 (Advanced)",
            "专业 (Professional)",
            "大师 (Master)"
        )

        // Preserve current game mode and AI color before recreating controller
        val currentMode = gameController.getGameMode()
        val currentAIColor = gameController.getAIColor()

        AlertDialog.Builder(this)
            .setTitle("选择AI难度")
            .setItems(difficulties) { _, which ->
                val difficulty = when (which) {
                    0 -> GameController.AIDifficulty.BEGINNER
                    1 -> GameController.AIDifficulty.INTERMEDIATE
                    2 -> GameController.AIDifficulty.ADVANCED
                    3 -> GameController.AIDifficulty.PROFESSIONAL
                    4 -> GameController.AIDifficulty.MASTER
                    else -> GameController.AIDifficulty.PROFESSIONAL
                }

                // Recreate game controller with new difficulty
                gameController.destroy()
                gameController = GameController(difficulty)
                setupGameController()

                // Restore the game mode and AI color
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
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
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
        gameController.destroy()
        audioManager.release()
    }
}
