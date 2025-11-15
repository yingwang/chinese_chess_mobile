package com.yingwang.chinesechess.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.yingwang.chinesechess.model.*

/**
 * Custom view for rendering the Chinese chess board
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var board: Board = Board.createInitialBoard()
    private var selectedPosition: Position? = null
    private var legalMoves: List<Move> = emptyList()
    private var onMoveListener: ((Move) -> Unit)? = null

    // Drawing properties
    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 179, 92)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val thickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val redPiecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val blackPiecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 255, 0)
        style = Paint.Style.FILL
    }

    private val legalMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 0, 255)
        style = Paint.Style.FILL
    }

    private val lastMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 0)
        style = Paint.Style.FILL
    }

    private val movedPieceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 165, 0) // Orange color
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastMove: Move? = null

    fun setBoard(newBoard: Board) {
        board = newBoard
        invalidate()
    }

    fun setOnMoveListener(listener: (Move) -> Unit) {
        onMoveListener = listener
    }

    fun highlightMove(move: Move?) {
        lastMove = move
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Calculate cell size based on available space
        val boardWidth = w - paddingLeft - paddingRight
        val boardHeight = h - paddingTop - paddingBottom

        cellSize = minOf(boardWidth / 9f, boardHeight / 10f)

        // Center the board
        offsetX = paddingLeft + (boardWidth - cellSize * 8) / 2
        offsetY = paddingTop + (boardHeight - cellSize * 9) / 2

        // Update text size
        textPaint.textSize = cellSize * 0.6f
        redTextPaint.textSize = cellSize * 0.6f
        blackTextPaint.textSize = cellSize * 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw board background
        canvas.drawRect(
            offsetX - cellSize / 2,
            offsetY - cellSize / 2,
            offsetX + cellSize * 8.5f,
            offsetY + cellSize * 9.5f,
            boardPaint
        )

        drawGrid(canvas)
        drawRiverText(canvas)
        drawPalaceLines(canvas)
        drawCannonPositions(canvas)
        drawLastMove(canvas)
        drawSelection(canvas)
        drawPieces(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        // Horizontal lines
        for (row in 0..9) {
            val y = offsetY + row * cellSize
            canvas.drawLine(offsetX, y, offsetX + cellSize * 8, y, linePaint)
        }

        // Vertical lines (split by river)
        for (col in 0..8) {
            val x = offsetX + col * cellSize

            // Top half (rows 0-4)
            canvas.drawLine(x, offsetY, x, offsetY + cellSize * 4, linePaint)

            // Bottom half (rows 5-9)
            canvas.drawLine(x, offsetY + cellSize * 5, x, offsetY + cellSize * 9, linePaint)
        }

        // Draw border with thick lines
        canvas.drawRect(
            offsetX,
            offsetY,
            offsetX + cellSize * 8,
            offsetY + cellSize * 9,
            thickLinePaint
        )
    }

    private fun drawRiverText(canvas: Canvas) {
        val riverY = offsetY + cellSize * 4.5f

        // Chinese text for river (optional, can be omitted)
        textPaint.textSize = cellSize * 0.4f
        canvas.drawText("楚河", offsetX + cellSize * 2, riverY, textPaint)
        canvas.drawText("汉界", offsetX + cellSize * 6, riverY, textPaint)
        textPaint.textSize = cellSize * 0.6f
    }

    private fun drawPalaceLines(canvas: Canvas) {
        // Black palace (top)
        canvas.drawLine(
            offsetX + cellSize * 3, offsetY,
            offsetX + cellSize * 5, offsetY + cellSize * 2,
            linePaint
        )
        canvas.drawLine(
            offsetX + cellSize * 5, offsetY,
            offsetX + cellSize * 3, offsetY + cellSize * 2,
            linePaint
        )

        // Red palace (bottom)
        canvas.drawLine(
            offsetX + cellSize * 3, offsetY + cellSize * 7,
            offsetX + cellSize * 5, offsetY + cellSize * 9,
            linePaint
        )
        canvas.drawLine(
            offsetX + cellSize * 5, offsetY + cellSize * 7,
            offsetX + cellSize * 3, offsetY + cellSize * 9,
            linePaint
        )
    }

    private fun drawCannonPositions(canvas: Canvas) {
        // Draw position markers for cannons and soldiers
        val positions = listOf(
            Position(2, 1), Position(2, 7),
            Position(7, 1), Position(7, 7),
            Position(3, 0), Position(3, 2), Position(3, 4), Position(3, 6), Position(3, 8),
            Position(6, 0), Position(6, 2), Position(6, 4), Position(6, 6), Position(6, 8)
        )

        for (pos in positions) {
            drawPositionMarker(canvas, pos)
        }
    }

    private fun drawPositionMarker(canvas: Canvas, pos: Position) {
        val x = offsetX + pos.col * cellSize
        val y = offsetY + pos.row * cellSize
        val markerSize = cellSize * 0.1f

        linePaint.strokeWidth = 2f

        // Draw corner markers
        val corners = listOf(
            Pair(-1f, -1f), Pair(1f, -1f),
            Pair(-1f, 1f), Pair(1f, 1f)
        )

        for ((dx, dy) in corners) {
            val startX = x + dx * markerSize
            val startY = y + dy * markerSize

            // Skip markers that would overlap grid lines
            if (pos.col == 0 && dx < 0 || pos.col == 8 && dx > 0 ||
                pos.row == 0 && dy < 0 || pos.row == 9 && dy > 0) {
                continue
            }

            // Horizontal line
            canvas.drawLine(
                startX,
                startY,
                startX + dx * markerSize,
                startY,
                linePaint
            )

            // Vertical line
            canvas.drawLine(
                startX,
                startY,
                startX,
                startY + dy * markerSize,
                linePaint
            )
        }
    }

    private fun drawLastMove(canvas: Canvas) {
        lastMove?.let { move ->
            val fromX = offsetX + move.from.col * cellSize
            val fromY = offsetY + move.from.row * cellSize
            val toX = offsetX + move.to.col * cellSize
            val toY = offsetY + move.to.row * cellSize

            canvas.drawCircle(fromX, fromY, cellSize * 0.4f, lastMovePaint)
            canvas.drawCircle(toX, toY, cellSize * 0.4f, lastMovePaint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        selectedPosition?.let { pos ->
            val x = offsetX + pos.col * cellSize
            val y = offsetY + pos.row * cellSize
            canvas.drawCircle(x, y, cellSize * 0.4f, selectionPaint)

            // Draw legal move indicators
            for (move in legalMoves) {
                val moveX = offsetX + move.to.col * cellSize
                val moveY = offsetY + move.to.row * cellSize
                canvas.drawCircle(moveX, moveY, cellSize * 0.15f, legalMovePaint)
            }
        }
    }

    private fun drawPieces(canvas: Canvas) {
        for (piece in board.getAllPieces()) {
            drawPiece(canvas, piece)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece) {
        val x = offsetX + piece.position.col * cellSize
        val y = offsetY + piece.position.row * cellSize
        val radius = cellSize * 0.4f

        // Check if this is the piece that was just moved
        val isMovedPiece = lastMove?.to == piece.position

        // Draw piece circle
        val piecePaint = if (piece.color == PieceColor.RED) redPiecePaint else blackPiecePaint
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 222, 179)
            style = Paint.Style.FILL
        }

        canvas.drawCircle(x, y, radius, circlePaint)
        canvas.drawCircle(x, y, radius, linePaint)

        // Draw highlight border around moved piece
        if (isMovedPiece) {
            canvas.drawCircle(x, y, radius + cellSize * 0.08f, movedPieceHighlightPaint)
        }

        // Draw piece character
        val textPaint = if (piece.color == PieceColor.RED) redTextPaint else blackTextPaint
        val text = piece.type.getDisplayName(piece.color)

        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textY = y + textBounds.height() / 2f

        canvas.drawText(text, x, textY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchedPos = getTouchedPosition(event.x, event.y)

            touchedPos?.let { pos ->
                handleTouch(pos)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchedPosition(x: Float, y: Float): Position? {
        val col = ((x - offsetX + cellSize / 2) / cellSize).toInt()
        val row = ((y - offsetY + cellSize / 2) / cellSize).toInt()

        val position = Position(row, col)
        return if (position.isValid()) position else null
    }

    private fun handleTouch(pos: Position) {
        val piece = board.getPiece(pos)

        // If a piece is already selected, try to move
        selectedPosition?.let { selected ->
            val move = legalMoves.find { it.to == pos }
            if (move != null) {
                onMoveListener?.invoke(move)
                selectedPosition = null
                legalMoves = emptyList()
                invalidate()
                return
            }
        }

        // Select a new piece
        if (piece != null && piece.color == board.currentPlayer) {
            selectedPosition = pos
            legalMoves = piece.getLegalMoves(board).filter { move ->
                val testBoard = board.makeMove(move)
                !testBoard.isInCheck(piece.color)
            }
            invalidate()
        } else {
            selectedPosition = null
            legalMoves = emptyList()
            invalidate()
        }
    }

    fun clearSelection() {
        selectedPosition = null
        legalMoves = emptyList()
        invalidate()
    }
}
