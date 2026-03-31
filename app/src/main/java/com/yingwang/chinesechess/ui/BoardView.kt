package com.yingwang.chinesechess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.yingwang.chinesechess.R
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

    // Animation state
    private var animatingMove: Move? = null
    private var animationProgress: Float = 0f
    private var moveAnimator: ValueAnimator? = null
    private var preMoveBoard: Board? = null

    companion object {
        private const val MOVE_ANIMATION_DURATION = 200L
    }

    // ── Paint objects ──

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 150, 100)
        style = Paint.Style.FILL
    }

    private val boardShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
        setShadowLayer(12f, 4f, 4f, Color.argb(100, 0, 0, 0))
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 50, 30)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val thickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 30, 15)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 50, 30)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setShadowLayer(2f, 1f, 1f, Color.argb(40, 0, 0, 0))
    }

    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 20, 20)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setShadowLayer(2f, 1f, 1f, Color.argb(80, 0, 0, 0))
    }

    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 20, 20)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setShadowLayer(2f, 1f, 1f, Color.argb(60, 255, 255, 255))
    }

    private val pieceOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 70, 40)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val pieceInnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 100, 70, 40)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val pieceShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    private val movedPieceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 160, 60)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val lastMoveFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 200, 180, 100)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val lastMoveToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 220, 190, 80)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val legalMoveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 60, 60, 60)
        style = Paint.Style.FILL
    }

    private val captureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 220, 50, 50)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val captureCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 220, 50, 50)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 45, 25)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Reusable paint for grain drawing
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val knotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(10, 80, 40, 15)
        style = Paint.Style.FILL
    }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastMove: Move? = null

    // Bitmap textures
    private var boardBitmap: Bitmap? = null
    private var pieceBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        loadTextures()
    }

    private fun loadTextures() {
        try {
            val bd = ContextCompat.getDrawable(context, R.drawable.board_texture)
            boardBitmap = (bd as? BitmapDrawable)?.bitmap
                ?: BitmapFactory.decodeResource(resources, R.drawable.board_texture)

            val pd = ContextCompat.getDrawable(context, R.drawable.piece_texture)
            pieceBitmap = (pd as? BitmapDrawable)?.bitmap
                ?: BitmapFactory.decodeResource(resources, R.drawable.piece_texture)
        } catch (_: Exception) {
            // Fall back to code-drawn textures
        }
    }

    fun setBoard(newBoard: Board) {
        if (animatingMove != null) return // defer during animation
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

    // ── Animation ──

    fun animateMove(move: Move, preBoard: Board, onComplete: () -> Unit) {
        animatingMove = move
        preMoveBoard = preBoard
        animationProgress = 0f

        moveAnimator?.cancel()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                animationProgress = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animatingMove = null
                    preMoveBoard = null
                    animationProgress = 0f
                    onComplete()
                }
            })
            start()
        }
    }

    private fun getDrawX(piece: Piece): Float {
        val anim = animatingMove
        if (anim != null && piece.position == anim.from && animationProgress < 1f) {
            val fromX = offsetX + anim.from.col * cellSize
            val toX = offsetX + anim.to.col * cellSize
            return fromX + (toX - fromX) * animationProgress
        }
        return offsetX + piece.position.col * cellSize
    }

    private fun getDrawY(piece: Piece): Float {
        val anim = animatingMove
        if (anim != null && piece.position == anim.from && animationProgress < 1f) {
            val fromY = offsetY + anim.from.row * cellSize
            val toY = offsetY + anim.to.row * cellSize
            return fromY + (toY - fromY) * animationProgress
        }
        return offsetY + piece.position.row * cellSize
    }

    // ── Size ──

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val boardWidth = w - paddingLeft - paddingRight
        val boardHeight = h - paddingTop - paddingBottom

        cellSize = minOf(boardWidth / 9f, boardHeight / 10f)

        offsetX = paddingLeft + (boardWidth - cellSize * 8) / 2
        offsetY = paddingTop + (boardHeight - cellSize * 9) / 2

        // Update text sizes
        riverTextPaint.textSize = cellSize * 0.5f
        riverTextPaint.letterSpacing = 0.4f
        redTextPaint.textSize = cellSize * 0.48f
        blackTextPaint.textSize = cellSize * 0.48f
    }

    // ── Drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val boardLeft = offsetX - cellSize / 2
        val boardTop = offsetY - cellSize / 2
        val boardRight = offsetX + cellSize * 8.5f
        val boardBottom = offsetY + cellSize * 9.5f

        // Board shadow
        canvas.drawRoundRect(
            boardLeft + 4f, boardTop + 4f,
            boardRight + 4f, boardBottom + 4f,
            8f, 8f, boardShadowPaint
        )

        // Board background with texture or fallback
        val bmp = boardBitmap
        if (bmp != null) {
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = RectF(boardLeft, boardTop, boardRight, boardBottom)
            canvas.save()
            val path = Path()
            path.addRoundRect(dst, 8f, 8f, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, src, dst, bitmapPaint)
            canvas.restore()
        } else {
            canvas.drawRoundRect(boardLeft, boardTop, boardRight, boardBottom, 8f, 8f, boardPaint)
            drawWoodGrain(canvas)
        }
        drawGrid(canvas)
        drawRiverText(canvas)
        drawPalaceLines(canvas)
        drawCannonPositions(canvas)
        drawLastMove(canvas)
        drawSelection(canvas)
        drawPieces(canvas)
    }

    private fun drawWoodGrain(canvas: Canvas) {
        val boardLeft = offsetX - cellSize / 2
        val boardTop = offsetY - cellSize / 2
        val boardRight = offsetX + cellSize * 8.5f
        val boardBottom = offsetY + cellSize * 9.5f

        // Layer 1: Vertical wood grain gradient
        val woodGradient = LinearGradient(
            boardLeft, boardTop, boardRight, boardTop,
            intArrayOf(
                Color.rgb(175, 135, 90),
                Color.rgb(195, 160, 115),
                Color.rgb(180, 140, 95),
                Color.rgb(200, 165, 120),
                Color.rgb(175, 135, 90)
            ),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = woodGradient
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boardLeft, boardTop, boardRight, boardBottom, 8f, 8f, gradientPaint)

        // Layer 2: Horizontal grain lines with subtle wave
        val totalHeight = boardBottom - boardTop
        val path = Path()
        for (i in 0..40) {
            val baseY = boardTop + (totalHeight / 40f) * i
            val alpha = if (i % 3 == 0) 25 else 12
            grainPaint.color = Color.argb(alpha, 120, 70, 30)

            path.reset()
            path.moveTo(boardLeft, baseY)
            val segments = 8
            val segWidth = (boardRight - boardLeft) / segments
            for (s in 1..segments) {
                val cx = boardLeft + segWidth * (s - 0.5f)
                val cy = baseY + (if (s % 2 == 0) 1.5f else -1.5f)
                val ex = boardLeft + segWidth * s
                path.quadTo(cx, cy, ex, baseY)
            }
            canvas.drawPath(path, grainPaint)
        }

        // Layer 3: Subtle knot accents
        canvas.drawOval(
            boardLeft + cellSize * 2, boardTop + cellSize * 3,
            boardLeft + cellSize * 3.5f, boardTop + cellSize * 4.5f,
            knotPaint
        )
        canvas.drawOval(
            boardLeft + cellSize * 5, boardTop + cellSize * 7,
            boardLeft + cellSize * 6.5f, boardTop + cellSize * 8f,
            knotPaint
        )
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
            canvas.drawLine(x, offsetY, x, offsetY + cellSize * 4, linePaint)
            canvas.drawLine(x, offsetY + cellSize * 5, x, offsetY + cellSize * 9, linePaint)
        }

        // Thick outer border
        canvas.drawRect(
            offsetX, offsetY,
            offsetX + cellSize * 8, offsetY + cellSize * 9,
            thickLinePaint
        )
    }

    private fun drawRiverText(canvas: Canvas) {
        val riverY = offsetY + cellSize * 4.5f
        val textBounds = Rect()
        riverTextPaint.getTextBounds("楚", 0, 1, textBounds)
        val textY = riverY + textBounds.height() / 2f

        canvas.drawText("楚 河", offsetX + cellSize * 1.5f, textY, riverTextPaint)
        canvas.drawText("漢 界", offsetX + cellSize * 6.5f, textY, riverTextPaint)
    }

    private fun drawPalaceLines(canvas: Canvas) {
        // Black palace (top)
        canvas.drawLine(
            offsetX + cellSize * 3, offsetY,
            offsetX + cellSize * 5, offsetY + cellSize * 2, linePaint
        )
        canvas.drawLine(
            offsetX + cellSize * 5, offsetY,
            offsetX + cellSize * 3, offsetY + cellSize * 2, linePaint
        )
        // Red palace (bottom)
        canvas.drawLine(
            offsetX + cellSize * 3, offsetY + cellSize * 7,
            offsetX + cellSize * 5, offsetY + cellSize * 9, linePaint
        )
        canvas.drawLine(
            offsetX + cellSize * 5, offsetY + cellSize * 7,
            offsetX + cellSize * 3, offsetY + cellSize * 9, linePaint
        )
    }

    private fun drawCannonPositions(canvas: Canvas) {
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
        val markerSize = cellSize * 0.12f
        val gap = cellSize * 0.04f

        val corners = listOf(
            -1f to -1f, 1f to -1f,
            -1f to 1f, 1f to 1f
        )

        for ((dx, dy) in corners) {
            if ((pos.col == 0 && dx < 0) || (pos.col == 8 && dx > 0) ||
                (pos.row == 0 && dy < 0) || (pos.row == 9 && dy > 0)) continue

            val sx = x + dx * gap
            val sy = y + dy * gap
            // Horizontal arm
            canvas.drawLine(sx + dx * markerSize * 0.2f, sy, sx + dx * markerSize, sy, markerPaint)
            // Vertical arm
            canvas.drawLine(sx, sy + dy * markerSize * 0.2f, sx, sy + dy * markerSize, markerPaint)
        }
    }

    private fun drawLastMove(canvas: Canvas) {
        lastMove?.let { move ->
            val halfCell = cellSize * 0.35f
            // "From" square outline
            val fromX = offsetX + move.from.col * cellSize
            val fromY = offsetY + move.from.row * cellSize
            canvas.drawRect(
                fromX - halfCell, fromY - halfCell,
                fromX + halfCell, fromY + halfCell,
                lastMoveFromPaint
            )
            // "To" square outline
            val toX = offsetX + move.to.col * cellSize
            val toY = offsetY + move.to.row * cellSize
            canvas.drawRect(
                toX - halfCell, toY - halfCell,
                toX + halfCell, toY + halfCell,
                lastMoveToPaint
            )
        }
    }

    private fun drawSelection(canvas: Canvas) {
        selectedPosition?.let { pos ->
            val x = offsetX + pos.col * cellSize
            val y = offsetY + pos.row * cellSize
            val radius = cellSize * 0.4f

            // Multi-layer warm gold glow
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
            }
            for (i in 3 downTo 1) {
                val glowRadius = radius + cellSize * 0.05f * i
                glowPaint.color = Color.argb(30 + (3 - i) * 25, 255, 200, 80)
                canvas.drawCircle(x, y, glowRadius, glowPaint)
            }

            // Solid selection ring
            val selRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 240, 180, 50)
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
            }
            canvas.drawCircle(x, y, radius + 2f, selRingPaint)

            // Draw legal move indicators
            for (move in legalMoves) {
                drawLegalMoveIndicator(canvas, move)
            }
        }
    }

    private fun drawLegalMoveIndicator(canvas: Canvas, move: Move) {
        val x = offsetX + move.to.col * cellSize
        val y = offsetY + move.to.row * cellSize

        if (move.capturedPiece != null) {
            // Capture: red ring + corner brackets
            canvas.drawCircle(x, y, cellSize * 0.42f, captureRingPaint)
            drawCornerCaptureMark(canvas, x, y)
        } else {
            // Normal: small semi-transparent dot
            canvas.drawCircle(x, y, cellSize * 0.1f, legalMoveDotPaint)
        }
    }

    private fun drawCornerCaptureMark(canvas: Canvas, cx: Float, cy: Float) {
        val size = cellSize * 0.42f
        val cornerLen = cellSize * 0.1f
        val corners = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
        for ((dx, dy) in corners) {
            val x0 = cx + dx * size
            val y0 = cy + dy * size
            canvas.drawLine(x0, y0, x0 - dx * cornerLen, y0, captureCornerPaint)
            canvas.drawLine(x0, y0, x0, y0 - dy * cornerLen, captureCornerPaint)
        }
    }

    // ── Pieces ──

    private fun drawPieces(canvas: Canvas) {
        val drawBoard = if (animatingMove != null) preMoveBoard ?: board else board
        for (piece in drawBoard.getAllPieces()) {
            val anim = animatingMove
            // During animation, fade out the captured piece
            if (anim != null && anim.capturedPiece != null && piece.position == anim.to) {
                val alpha = ((1f - animationProgress) * 255).toInt().coerceIn(0, 255)
                drawPiece(canvas, piece, alpha)
                continue
            }
            drawPiece(canvas, piece, 255)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, alpha: Int = 255) {
        val x = getDrawX(piece)
        val y = getDrawY(piece)
        val radius = cellSize * 0.4f
        val isMovedPiece = lastMove?.to == piece.position && animatingMove == null

        // Drop shadow
        if (alpha > 128) {
            canvas.drawCircle(x + 4f, y + 5f, radius + 1f, pieceShadowPaint)
        }

        val pbmp = pieceBitmap
        if (pbmp != null) {
            // Draw piece using bitmap texture
            val src = Rect(0, 0, pbmp.width, pbmp.height)
            val dst = RectF(x - radius, y - radius, x + radius, y + radius)
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(pbmp, src, dst, bitmapPaint)
            bitmapPaint.alpha = 255
        } else {
            // Fallback: code-drawn piece
            val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 160, 130, 95)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(x, y + 1.5f, radius, edgePaint)

            val gradient = RadialGradient(
                x - radius * 0.3f, y - radius * 0.3f, radius * 1.3f,
                intArrayOf(
                    Color.rgb(255, 250, 235),
                    Color.rgb(245, 230, 200),
                    Color.rgb(220, 195, 160),
                    Color.rgb(175, 145, 110)
                ),
                floatArrayOf(0f, 0.3f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
                style = Paint.Style.FILL
                this.alpha = alpha
            }
            canvas.drawCircle(x, y, radius, circlePaint)

            pieceOutlinePaint.alpha = alpha
            canvas.drawCircle(x, y, radius, pieceOutlinePaint)
            pieceOutlinePaint.alpha = 255

            pieceInnerRingPaint.alpha = (alpha * 80 / 255).coerceIn(0, 255)
            canvas.drawCircle(x, y, radius * 0.72f, pieceInnerRingPaint)
            pieceInnerRingPaint.alpha = 80
        }

        // Moved piece highlight
        if (isMovedPiece) {
            canvas.drawCircle(x, y, radius + 3f, movedPieceHighlightPaint)
        }

        // Character text — use FontMetrics for precise vertical centering
        val textPaint = if (piece.color == PieceColor.RED) redTextPaint else blackTextPaint
        val text = piece.type.getDisplayName(piece.color)
        val fm = textPaint.fontMetrics
        val textY = y - (fm.ascent + fm.descent) / 2f
        val savedAlpha = textPaint.alpha
        textPaint.alpha = alpha
        canvas.drawText(text, x, textY, textPaint)
        textPaint.alpha = savedAlpha
    }

    // ── Touch handling ──

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animatingMove != null) return true // block during animation

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
        selectedPosition?.let {
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
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
