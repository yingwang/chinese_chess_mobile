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
import com.yingwang.chinesechess.model.PieceColor
import com.yingwang.chinesechess.ui.BoardView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupGameController()
        gameController.startNewGame()
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
        redScoreText.text = "红方: ${stats.redScore}"
        blackScoreText.text = "黑方: ${stats.blackScore}"

        val minutes = stats.gameTime / 60000
        val seconds = (stats.gameTime % 60000) / 1000
        gameTimeText.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun updateGameModeDisplay() {
        val modeText = when (gameController.getGameMode()) {
            GameController.GameMode.PLAYER_VS_PLAYER -> "游戏模式: 玩家 vs 玩家"
            GameController.GameMode.PLAYER_VS_AI -> {
                val playerColor = if (gameController.getAIColor() == PieceColor.RED) "黑方" else "红方"
                "游戏模式: 玩家($playerColor) vs AI"
            }
            GameController.GameMode.AI_VS_AI -> "游戏模式: AI vs AI"
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
