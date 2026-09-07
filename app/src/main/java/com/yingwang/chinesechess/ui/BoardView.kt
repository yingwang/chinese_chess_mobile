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
import java.util.Random

/**
 * Renders the board and pieces.
 *
 * The board itself is drawn in code from the `board_*` colour tokens: a framed slab of
 * honey-coloured wood with a light grain, a double border, palace lines, position marks,
 * the river text and file numbers. Everything static is rendered once per size into a
 * bitmap, so a frame during a move animation only blits that bitmap and draws the pieces.
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
        /** Horizontal margin outside the grid, in cells. */
        private const val MARGIN_X = 0.5f
        /** Vertical margin outside the grid, in cells; wider so the file numbers fit. */
        private const val MARGIN_Y = 0.8f
        const val ASPECT_HEIGHT_CELLS = 9f + 2 * MARGIN_Y
        private val TOP_FILES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        private val BOTTOM_FILES = listOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
    }

    // ── Colours ──

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private val surfaceLight = color(R.color.board_surface_light)
    private val surfaceMid = color(R.color.board_surface_mid)
    private val surfaceDark = color(R.color.board_surface_dark)
    private val grainColor = color(R.color.board_grain)
    private val lineColor = color(R.color.board_line)
    private val frameColor = color(R.color.board_frame)
    private val frameLightColor = color(R.color.board_frame_light)
    private val riverColor = color(R.color.board_river)
    private val coordColor = color(R.color.board_coord)
    private val redInk = color(R.color.chess_piece_red_ink)
    private val blackInk = color(R.color.chess_piece_black_ink)

    // ── Paints ──

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = frameColor
        style = Paint.Style.FILL
        setShadowLayer(14f, 0f, 6f, Color.argb(120, 0, 0, 0))
    }

    private val frameBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = frameLightColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
    }

    private val thickLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val riverTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = riverColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = coordColor
        alpha = 190
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = redInk
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setShadowLayer(2f, 1f, 1f, Color.argb(70, 0, 0, 0))
    }

    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blackInk
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
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    private val movedPieceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 160, 60)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val lastMoveFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 200, 170, 90)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val lastMoveToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 220, 180, 70)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val legalMoveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 50, 40, 30)
        style = Paint.Style.FILL
    }

    private val captureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 210, 50, 50)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val captureCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 210, 50, 50)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val selectionGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 240, 180, 50)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastMove: Move? = null

    private var pieceBitmap: Bitmap? = null
    private var surfaceBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        // BlurMaskFilter and shadow layers need software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        loadPieceTexture()
    }

    private fun loadPieceTexture() {
        try {
            val pd = ContextCompat.getDrawable(context, R.drawable.piece_texture)
            pieceBitmap = (pd as? BitmapDrawable)?.bitmap
                ?: BitmapFactory.decodeResource(resources, R.drawable.piece_texture)
        } catch (_: Exception) {
            // Fall back to the code-drawn disc.
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

    // ── Measure & size ──

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val availableWidth = widthSize - paddingLeft - paddingRight
        val availableHeight = heightSize - paddingTop - paddingBottom

        if (availableWidth > 0 && availableHeight > 0) {
            val cell = minOf(availableWidth / 9f, availableHeight / ASPECT_HEIGHT_CELLS)
            val desiredWidth = (cell * 9f).toInt() + paddingLeft + paddingRight
            val desiredHeight = (cell * ASPECT_HEIGHT_CELLS).toInt() + paddingTop + paddingBottom
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDimensions(w, h)
    }

    private fun updateDimensions(w: Int, h: Int) {
        val boardWidth = w - paddingLeft - paddingRight
        val boardHeight = h - paddingTop - paddingBottom
        if (boardWidth <= 0 || boardHeight <= 0) return

        cellSize = minOf(boardWidth / 9f, boardHeight / ASPECT_HEIGHT_CELLS)

        offsetX = paddingLeft + (boardWidth - cellSize * 8) / 2
        offsetY = paddingTop + (boardHeight - cellSize * 9) / 2

        linePaint.strokeWidth = (cellSize * 0.022f).coerceAtLeast(1.5f)
        thickLinePaint.strokeWidth = (cellSize * 0.045f).coerceAtLeast(3f)
        markerPaint.strokeWidth = (cellSize * 0.025f).coerceAtLeast(1.5f)
        riverTextPaint.textSize = cellSize * 0.56f
        riverTextPaint.letterSpacing = 0.55f
        coordPaint.textSize = cellSize * 0.2f
        redTextPaint.textSize = cellSize * 0.5f
        blackTextPaint.textSize = cellSize * 0.5f

        surfaceBitmap?.recycle()
        surfaceBitmap = buildSurface(w, h)
    }

    // ── Static layer ──

    private fun boardRect() = RectF(
        offsetX - cellSize * MARGIN_X,
        offsetY - cellSize * MARGIN_Y,
        offsetX + cellSize * (8 + MARGIN_X),
        offsetY + cellSize * (9 + MARGIN_Y)
    )

    private fun buildSurface(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val frame = boardRect()
        val frameWidth = cellSize * 0.09f
        val outerRadius = cellSize * 0.14f
        // Keep the drop shadow inside the view: pull the frame in by a hair so the blur has room.
        c.drawRoundRect(frame, outerRadius, outerRadius, framePaint)

        val inner = RectF(frame).apply { inset(frameWidth, frameWidth) }
        val innerRadius = outerRadius * 0.5f
        val clip = Path().apply { addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW) }

        c.save()
        c.clipPath(clip)
        drawWood(c, inner)
        c.restore()

        // Bevel: a light hairline just inside the frame edge, and a dark one at the wood edge.
        c.drawRoundRect(
            RectF(frame).apply { inset(2f, 2f) }, outerRadius - 2f, outerRadius - 2f, frameBevelPaint
        )
        c.drawRoundRect(inner, innerRadius, innerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 40, 22, 8)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        })

        drawGrid(c)
        drawPalaceLines(c)
        drawPositionMarks(c)
        drawRiverText(c)
        drawCoordinates(c)
        return bmp
    }

    private fun drawWood(c: Canvas, r: RectF) {
        // Planks: alternate light and dark bands across the width.
        val bands = LinearGradient(
            r.left, r.top, r.right, r.top,
            intArrayOf(surfaceMid, surfaceLight, surfaceDark, surfaceLight, surfaceMid, surfaceLight, surfaceDark, surfaceMid),
            floatArrayOf(0f, 0.14f, 0.3f, 0.45f, 0.58f, 0.72f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bands })

        // Grain: deterministic wavy vertical strokes, mostly faint, a few darker streaks.
        val rnd = Random(20260907L)
        val path = Path()
        val count = 110
        for (i in 0 until count) {
            val x0 = r.left + rnd.nextFloat() * r.width()
            val dark = rnd.nextInt(9) == 0
            grainPaint.color = grainColor
            grainPaint.alpha = if (dark) 18 + rnd.nextInt(10) else 6 + rnd.nextInt(11)
            grainPaint.strokeWidth = if (dark) 1.6f + rnd.nextFloat() * 1.4f else 0.8f + rnd.nextFloat() * 1.0f

            path.reset()
            path.moveTo(x0, r.top)
            val segments = 6
            val segH = r.height() / segments
            var x = x0
            for (s in 1..segments) {
                val wobble = (rnd.nextFloat() - 0.5f) * cellSize * 0.08f
                val nx = x0 + wobble
                path.quadTo(x + (rnd.nextFloat() - 0.5f) * cellSize * 0.1f, r.top + segH * (s - 0.5f), nx, r.top + segH * s)
                x = nx
            }
            c.drawPath(path, grainPaint)
        }

        // Soft vignette so the slab reads as a solid object rather than a flat fill.
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = maxOf(r.width(), r.height()) * 0.72f
        val vignette = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(70, 60, 30, 8)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = vignette })
    }

    private fun drawGrid(c: Canvas) {
        for (row in 0..9) {
            val y = offsetY + row * cellSize
            c.drawLine(offsetX, y, offsetX + cellSize * 8, y, linePaint)
        }
        for (col in 0..8) {
            val x = offsetX + col * cellSize
            c.drawLine(x, offsetY, x, offsetY + cellSize * 4, linePaint)
            c.drawLine(x, offsetY + cellSize * 5, x, offsetY + cellSize * 9, linePaint)
        }
        // Traditional double border: a heavy line on the grid edge and a thin one just outside.
        c.drawRect(offsetX, offsetY, offsetX + cellSize * 8, offsetY + cellSize * 9, thickLinePaint)
        val gap = cellSize * 0.07f
        c.drawRect(
            offsetX - gap, offsetY - gap,
            offsetX + cellSize * 8 + gap, offsetY + cellSize * 9 + gap,
            linePaint
        )
    }

    private fun drawPalaceLines(c: Canvas) {
        // Black palace (top)
        c.drawLine(offsetX + cellSize * 3, offsetY, offsetX + cellSize * 5, offsetY + cellSize * 2, linePaint)
        c.drawLine(offsetX + cellSize * 5, offsetY, offsetX + cellSize * 3, offsetY + cellSize * 2, linePaint)
        // Red palace (bottom)
        c.drawLine(offsetX + cellSize * 3, offsetY + cellSize * 7, offsetX + cellSize * 5, offsetY + cellSize * 9, linePaint)
        c.drawLine(offsetX + cellSize * 5, offsetY + cellSize * 7, offsetX + cellSize * 3, offsetY + cellSize * 9, linePaint)
    }

    private fun drawPositionMarks(c: Canvas) {
        val positions = listOf(
            Position(2, 1), Position(2, 7),
            Position(7, 1), Position(7, 7),
            Position(3, 0), Position(3, 2), Position(3, 4), Position(3, 6), Position(3, 8),
            Position(6, 0), Position(6, 2), Position(6, 4), Position(6, 6), Position(6, 8)
        )
        for (pos in positions) drawPositionMark(c, pos)
    }

    private fun drawPositionMark(c: Canvas, pos: Position) {
        val x = offsetX + pos.col * cellSize
        val y = offsetY + pos.row * cellSize
        val arm = cellSize * 0.13f
        val gap = cellSize * 0.05f
        val corners = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
        for ((dx, dy) in corners) {
            if ((pos.col == 0 && dx < 0) || (pos.col == 8 && dx > 0)) continue
            val sx = x + dx * gap
            val sy = y + dy * gap
            c.drawLine(sx, sy, sx + dx * arm, sy, markerPaint)
            c.drawLine(sx, sy, sx, sy + dy * arm, markerPaint)
        }
    }

    private fun drawRiverText(c: Canvas) {
        val riverY = offsetY + cellSize * 4.5f
        val fm = riverTextPaint.fontMetrics
        val textY = riverY - (fm.ascent + fm.descent) / 2f
        riverTextPaint.alpha = 215
        c.drawText("楚河", offsetX + cellSize * 2f, textY, riverTextPaint)
        c.drawText("漢界", offsetX + cellSize * 6f, textY, riverTextPaint)
    }

    private fun drawCoordinates(c: Canvas) {
        val fm = coordPaint.fontMetrics
        val topY = offsetY - cellSize * 0.58f - (fm.ascent + fm.descent) / 2f
        val bottomY = offsetY + cellSize * 9.58f - (fm.ascent + fm.descent) / 2f
        for (col in 0..8) {
            val x = offsetX + col * cellSize
            c.drawText(TOP_FILES[col], x, topY, coordPaint)
            c.drawText(BOTTOM_FILES[col], x, bottomY, coordPaint)
        }
    }

    // ── Drawing ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) {
            updateDimensions(width, height)
            if (cellSize <= 0f) return
        }

        val surface = surfaceBitmap
        if (surface != null) {
            canvas.drawBitmap(surface, 0f, 0f, null)
        } else {
            val r = boardRect()
            canvas.drawRoundRect(r, 8f, 8f, framePaint)
            drawGrid(canvas)
        }
        drawLastMove(canvas)
        drawSelection(canvas)
        drawPieces(canvas)
    }

    private fun drawLastMove(canvas: Canvas) {
        lastMove?.let { move ->
            val halfCell = cellSize * 0.35f
            val fromX = offsetX + move.from.col * cellSize
            val fromY = offsetY + move.from.row * cellSize
            canvas.drawRect(fromX - halfCell, fromY - halfCell, fromX + halfCell, fromY + halfCell, lastMoveFromPaint)
            val toX = offsetX + move.to.col * cellSize
            val toY = offsetY + move.to.row * cellSize
            canvas.drawRect(toX - halfCell, toY - halfCell, toX + halfCell, toY + halfCell, lastMoveToPaint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        selectedPosition?.let { pos ->
            val x = offsetX + pos.col * cellSize
            val y = offsetY + pos.row * cellSize
            val radius = cellSize * 0.4f

            for (i in 3 downTo 1) {
                val glowRadius = radius + cellSize * 0.05f * i
                selectionGlowPaint.color = Color.argb(30 + (3 - i) * 25, 255, 200, 80)
                canvas.drawCircle(x, y, glowRadius, selectionGlowPaint)
            }
            canvas.drawCircle(x, y, radius + 2f, selectionRingPaint)

            for (move in legalMoves) drawLegalMoveIndicator(canvas, move)
        }
    }

    private fun drawLegalMoveIndicator(canvas: Canvas, move: Move) {
        val x = offsetX + move.to.col * cellSize
        val y = offsetY + move.to.row * cellSize
        if (move.capturedPiece != null) {
            canvas.drawCircle(x, y, cellSize * 0.42f, captureRingPaint)
            drawCornerCaptureMark(canvas, x, y)
        } else {
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

        if (alpha > 128) {
            canvas.drawCircle(x + 3f, y + 5f, radius + 1f, pieceShadowPaint)
        }

        val pbmp = pieceBitmap
        if (pbmp != null) {
            val src = Rect(0, 0, pbmp.width, pbmp.height)
            val dst = RectF(x - radius, y - radius, x + radius, y + radius)
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(pbmp, src, dst, bitmapPaint)
            bitmapPaint.alpha = 255
        } else {
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

        if (isMovedPiece) {
            canvas.drawCircle(x, y, radius + 3f, movedPieceHighlightPaint)
        }

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
