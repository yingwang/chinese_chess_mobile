package com.yingwang.chinesechess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.ContextCompat
import com.yingwang.chinesechess.R
import com.yingwang.chinesechess.model.*
import java.util.Random

/**
 * Renders the board and pieces.
 *
 * The board itself is drawn in code from the `board_*` colour tokens: a framed slab of
 * pale jade with faint mineral veins, a double border, palace lines, position marks,
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
    /** Red's files along the bottom: Chinese numerals, or 9…1 where the moves are written in WXF. */
    private val bottomFiles: Array<String> = resources.getStringArray(R.array.board_bottom_files)
    private val redInk = color(R.color.chess_piece_red_ink)
    private val blackInk = color(R.color.chess_piece_black_ink)

    private val calligraphy = ResourcesCompat.getFont(context, R.font.jade_chess_kai)
        ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    // ── Paints ──

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = frameColor
        style = Paint.Style.FILL
        setShadowLayer(7f, 0f, 3f, Color.argb(55, 48, 65, 42))
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
        typeface = calligraphy
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

    // No text shadow: on a small piece it only blurs the stroke of the character.
    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = redInk
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blackInk
        textAlign = Paint.Align.CENTER
        typeface = calligraphy
    }

    /**
     * Soft radial shading gives the jade discs depth without compromising the engraved glyphs.
     */
    private val pieceFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 242, 230, 205)
        style = Paint.Style.FILL
    }

    private val pieceSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pieceHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
        color = Color.argb(55, 55, 70, 43)
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }

    // Three marks, three looks: the last move is amber corner brackets (faint where it came
    // from, solid where it went), the selected piece a solid celadon ring, and a general in
    // check a red ring. They used to be variations on the same gold glow.
    private val lastMoveFromPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 196, 120, 20)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val lastMoveToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 196, 120, 20)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val checkRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 35, 35)
        strokeWidth = 4.5f
        style = Paint.Style.STROKE
    }

    private val checkGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 230, 40, 40)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
    }

    // The move marks take their colours from the theme: dark on the pale board, light on the
    // night one, where the old dark dots could hardly be seen.
    private val legalMoveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_move_dot)
        style = Paint.Style.FILL
    }

    private val captureRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_capture_mark)
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    private val captureCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_capture_mark)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.board_selection)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var cellSize = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastMove: Move? = null
    /** A move the player is being shown (the better move in a review), drawn as an arrow. */
    private var suggestion: Move? = null

    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 28, 128, 112)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val suggestionHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 28, 128, 112)
        style = Paint.Style.FILL
    }

    private var surfaceBitmap: Bitmap? = null

    init {
        // BlurMaskFilter and shadow layers need software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /**
     * Turned round for a player on black in an online game: black's pieces at the bottom. Only
     * the drawing and the touch turn (see [BoardOrientation]); the file numbers swap edges.
     */
    var flipped = false
        set(value) {
            if (field == value) return
            field = value
            if (width > 0 && height > 0) {
                surfaceBitmap?.recycle()
                surfaceBitmap = buildSurface(width, height)
            }
            invalidate()
        }

    /** When set, only pieces of this colour can be picked up (your own side in an online game). */
    var selectableColor: PieceColor? = null
        set(value) {
            if (field == value) return
            field = value
            clearSelection()
        }

    /** Centre of [pos] on screen, after turning the board round if it is [flipped]. */
    private fun cellX(pos: Position): Float = offsetX + BoardOrientation.toScreen(pos, flipped).col * cellSize
    private fun cellY(pos: Position): Float = offsetY + BoardOrientation.toScreen(pos, flipped).row * cellSize

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

    fun showSuggestion(move: Move?) {
        suggestion = move
        invalidate()
    }

    private fun drawSuggestion(canvas: Canvas) {
        val move = suggestion ?: return
        val x0 = cellX(move.from)
        val y0 = cellY(move.from)
        val x1 = cellX(move.to)
        val y1 = cellY(move.to)
        val len = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (len <= 0f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val head = cellSize * 0.32f
        // Stop the shaft at the base of the head so the tip stays sharp.
        suggestionPaint.strokeWidth = cellSize * 0.12f
        canvas.drawLine(x0, y0, x1 - ux * head, y1 - uy * head, suggestionPaint)
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x1 - ux * head - uy * head * 0.6f, y1 - uy * head + ux * head * 0.6f)
            lineTo(x1 - ux * head + uy * head * 0.6f, y1 - uy * head - ux * head * 0.6f)
            close()
        }
        canvas.drawPath(path, suggestionHeadPaint)
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
            val fromX = cellX(anim.from)
            val toX = cellX(anim.to)
            return fromX + (toX - fromX) * animationProgress
        }
        return cellX(piece.position)
    }

    private fun getDrawY(piece: Piece): Float {
        val anim = animatingMove
        if (anim != null && piece.position == anim.from && animationProgress < 1f) {
            val fromY = cellY(anim.from)
            val toY = cellY(anim.to)
            return fromY + (toY - fromY) * animationProgress
        }
        return cellY(piece.position)
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

        linePaint.strokeWidth = (cellSize * 0.018f).coerceAtLeast(1.5f)
        thickLinePaint.strokeWidth = (cellSize * 0.03f).coerceAtLeast(3f)
        markerPaint.strokeWidth = (cellSize * 0.025f).coerceAtLeast(1.5f)
        riverTextPaint.textSize = cellSize * 0.56f
        riverTextPaint.letterSpacing = 0.55f
        coordPaint.textSize = cellSize * 0.2f
        redTextPaint.textSize = cellSize * 0.54f
        blackTextPaint.textSize = cellSize * 0.54f

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
            color = Color.argb(160, 166, 139, 89)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        })

        drawCornerInlays(c, inner)
        drawGrid(c)
        drawPalaceLines(c)
        drawPositionMarks(c)
        drawRiverText(c)
        drawCoordinates(c)
        return bmp
    }

    /** Translucent-looking jade with faint mineral veins; cached with the board geometry. */
    private fun drawWood(c: Canvas, r: RectF) {
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(surfaceLight, surfaceMid, surfaceLight, surfaceDark), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, wash)
        val random = Random(42L)
        grainPaint.color = grainColor
        grainPaint.alpha = 12
        grainPaint.strokeWidth = cellSize * 0.016f
        repeat(25) {
            val x = r.left + random.nextFloat() * r.width()
            val y = r.top + random.nextFloat() * r.height()
            val vein = Path().apply {
                moveTo(x, y)
                cubicTo(x + cellSize, y - cellSize, x + cellSize * 2, y + cellSize,
                    x + cellSize * 3, y + cellSize * 0.3f)
            }
            c.drawPath(vein, grainPaint)
        }
    }

    private fun drawCornerInlays(c: Canvas, bounds: RectF) {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = cellSize * 0.02f
            strokeJoin = Paint.Join.ROUND
        }
        for (corner in 0..3) {
            c.save()
            c.translate(if (corner % 2 == 0) bounds.left else bounds.right,
                if (corner < 2) bounds.top else bounds.bottom)
            c.scale(if (corner % 2 == 0) 1f else -1f, if (corner < 2) 1f else -1f)
            val u = cellSize * 0.10f
            val fret = Path().apply {
                moveTo(u, 5 * u); lineTo(u, u); lineTo(5 * u, u)
                moveTo(2 * u, 4 * u); lineTo(2 * u, 2 * u); lineTo(4 * u, 2 * u)
                lineTo(4 * u, 3 * u); lineTo(3 * u, 3 * u)
            }
            c.drawPath(fret, ink)
            c.restore()
        }
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
        // Each side's files run from its own right: black's 1-9 along black's edge, red's along
        // red's. Turned round, the edges swap and each row reads the other way.
        for (col in 0..8) {
            val x = offsetX + col * cellSize
            c.drawText(if (flipped) bottomFiles[8 - col] else TOP_FILES[col], x, topY, coordPaint)
            c.drawText(if (flipped) TOP_FILES[8 - col] else bottomFiles[col], x, bottomY, coordPaint)
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
        drawSuggestion(canvas)
    }

    private fun drawLastMove(canvas: Canvas) {
        lastMove?.let { move ->
            drawBrackets(canvas, move.from, lastMoveFromPaint)
            drawBrackets(canvas, move.to, lastMoveToPaint)
        }
    }

    /** Four corner brackets around an intersection, just outside a piece. */
    private fun drawBrackets(canvas: Canvas, pos: Position, paint: Paint) {
        val cx = cellX(pos)
        val cy = cellY(pos)
        val half = cellSize * 0.47f
        val arm = cellSize * 0.15f
        for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val x0 = cx + dx * half
            val y0 = cy + dy * half
            canvas.drawLine(x0, y0, x0 - dx * arm, y0, paint)
            canvas.drawLine(x0, y0, x0, y0 - dy * arm, paint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        selectedPosition?.let { pos ->
            val x = cellX(pos)
            val y = cellY(pos)
            val radius = cellSize * 0.4f
            canvas.drawCircle(x, y, radius + 3f, selectionRingPaint)

            for (move in legalMoves) drawLegalMoveIndicator(canvas, move)
        }
    }

    private fun drawLegalMoveIndicator(canvas: Canvas, move: Move) {
        val x = cellX(move.to)
        val y = cellY(move.to)
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

        if (alpha > 128) {
            canvas.drawCircle(x + 3f, y + 5f, radius + 1f, pieceShadowPaint)
        }

        val jade = piece.color == PieceColor.BLACK
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val face = if (night) intArrayOf(Color.rgb(250, 247, 241), Color.rgb(234, 229, 221), Color.rgb(193, 185, 178))
            else if (jade) intArrayOf(Color.rgb(247, 250, 239), Color.rgb(224, 234, 211), Color.rgb(183, 202, 174))
            else intArrayOf(Color.rgb(255, 253, 241), Color.rgb(243, 238, 218), Color.rgb(212, 202, 173))
        val thickness = cellSize * 0.055f
        pieceSidePaint.shader = LinearGradient(x, y, x, y + radius + thickness,
            if (night) Color.rgb(222, 216, 208) else if (jade) Color.rgb(191, 210, 180) else Color.rgb(229, 220, 191),
            if (night) Color.rgb(133, 123, 119) else if (jade) Color.rgb(115, 143, 111) else Color.rgb(163, 146, 110), Shader.TileMode.CLAMP)
        pieceSidePaint.alpha = alpha
        canvas.drawCircle(x, y + thickness, radius, pieceSidePaint)
        pieceFacePaint.shader = RadialGradient(x - radius * 0.28f, y - radius * 0.35f,
            radius * 1.6f, face, floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        pieceFacePaint.alpha = alpha
        canvas.drawCircle(x, y, radius, pieceFacePaint)
        pieceFacePaint.shader = null
        pieceOutlinePaint.color = if (night) Color.rgb(156, 142, 128) else if (jade) Color.rgb(141, 162, 134) else Color.rgb(179, 157, 115)
        pieceOutlinePaint.strokeWidth = cellSize * 0.025f
        pieceOutlinePaint.alpha = alpha
        canvas.drawCircle(x, y, radius * 0.95f, pieceOutlinePaint)
        pieceHighlightPaint.color = Color.rgb(255, 255, 245)
        pieceHighlightPaint.alpha = alpha * 220 / 255
        pieceHighlightPaint.strokeWidth = cellSize * 0.026f
        val rim = RectF(x - radius * 0.97f, y - radius * 0.97f, x + radius * 0.97f, y + radius * 0.97f)
        canvas.drawArc(rim, 195f, 145f, false, pieceHighlightPaint)
        pieceInnerRingPaint.color = if (night) Color.rgb(158, 145, 131) else if (jade) Color.rgb(136, 160, 126) else Color.rgb(175, 153, 112)
        pieceInnerRingPaint.strokeWidth = cellSize * 0.018f
        pieceInnerRingPaint.alpha = alpha * 150 / 255
        canvas.drawCircle(x, y, radius * 0.79f, pieceInnerRingPaint)

        if (piece.type == PieceType.GENERAL && animatingMove == null && board.isInCheck(piece.color)) {
            canvas.drawCircle(x, y, radius + 4f, checkGlowPaint)
            canvas.drawCircle(x, y, radius + 3f, checkRingPaint)
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

    /** False while the board only shows a position (a replay); a touch then reports [onBlockedTouch]. */
    var acceptsInput = true
        set(value) {
            field = value
            if (!value) clearSelection()
        }
    var onBlockedTouch: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animatingMove != null) return true // block during animation
        if (!acceptsInput) {
            if (event.action == MotionEvent.ACTION_DOWN) onBlockedTouch?.invoke()
            return true
        }

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
        if (!Position(row, col).isValid()) return null
        return BoardOrientation.fromScreen(row, col, flipped)
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

        if (piece != null && piece.color == board.currentPlayer && (selectableColor == null || piece.color == selectableColor)) {
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
