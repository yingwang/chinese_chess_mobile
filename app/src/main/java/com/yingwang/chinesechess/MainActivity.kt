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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupGameController()
        gameController.startNewGame()
        startTimerUpdates()
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

        val minutes = stats.gameTime / 60000
        val seconds = (stats.gameTime % 60000) / 1000
        gameTimeText.text = String.format("%02d:%02d", minutes, seconds)

        // Update move count
        moveCountText.text = "回合: ${stats.moveNumber}"

        // Update move history
        updateMoveHistory()
    }

    private fun updateMoveHistory() {
        val moves = gameController.getMoveHistory()
        if (moves.isEmpty()) {
            moveHistoryText.text = "棋谱:"
            return
        }

        val history = StringBuilder("棋谱: ")
        moves.forEachIndexed { index, move ->
            if (index > 0 && index % 2 == 0) {
                history.append("\n")
            }
            val moveNum = index / 2 + 1
            if (index % 2 == 0) {
                history.append("${moveNum}. ")
            }
            history.append(formatMove(move))
            if (index % 2 == 0 && index < moves.size - 1) {
                history.append(" ")
            }
        }
        moveHistoryText.text = history.toString()

        // Auto-scroll to bottom
        moveHistoryText.post {
            val scrollView = moveHistoryText.parent as? android.widget.ScrollView
            scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun formatMove(move: com.yingwang.chinesechess.model.Move): String {
        // Format move as "炮2进5" style notation
        val pieceChar = when (move.piece.type) {
            com.yingwang.chinesechess.model.PieceType.GENERAL -> "将"
            com.yingwang.chinesechess.model.PieceType.ADVISOR -> "士"
            com.yingwang.chinesechess.model.PieceType.ELEPHANT -> "象"
            com.yingwang.chinesechess.model.PieceType.HORSE -> "马"
            com.yingwang.chinesechess.model.PieceType.CHARIOT -> "车"
            com.yingwang.chinesechess.model.PieceType.CANNON -> "炮"
            com.yingwang.chinesechess.model.PieceType.SOLDIER -> "兵"
        }

        // Simplified notation: just show from->to coordinates
        return "${pieceChar}${move.from.row}${move.from.col}->${move.to.row}${move.to.col}"
    }

    private fun startTimerUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000) // Update every second
                val gameTime = System.currentTimeMillis() - gameController.getGameStartTime()
                val minutes = gameTime / 60000
                val seconds = (gameTime % 60000) / 1000
                gameTimeText.text = String.format("%02d:%02d", minutes, seconds)
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
    }
}
