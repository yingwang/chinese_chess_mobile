package com.yingwang.chinesechess.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yingwang.chinesechess.R
import kotlin.math.exp

/**
 * A slim bar showing the engine's view of the game: red's share fills from the left,
 * black's from the right. Centipawns map to a share through the usual logistic curve,
 * so +100 is about 59% and +400 about 80%; a forced mate pins the bar to one side.
 */
class EvalBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_red_side)
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_black_side)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chess_panel_stroke)
    }

    private var redShare = 0.5f
    private var animator: ValueAnimator? = null
    private val rect = RectF()

    /** @param cpRed centipawns from red's point of view; null resets to even. */
    fun setEvaluation(cpRed: Int?, mateRed: Int?) {
        val target = when {
            mateRed != null -> if (mateRed > 0) 0.97f else 0.03f
            cpRed != null -> (1f / (1f + exp(-0.00368208f * cpRed))).coerceIn(0.03f, 0.97f)
            else -> 0.5f
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(redShare, target).apply {
            duration = 350
            addUpdateListener {
                redShare = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        val split = (w * redShare).coerceIn(r, w - r)
        canvas.save()
        canvas.clipRect(0f, 0f, split, h)
        canvas.drawRoundRect(rect, r, r, redPaint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(split, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, blackPaint)
        canvas.restore()
    }
}
