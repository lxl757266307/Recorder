package com.kanade.recorder.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class RecorderButton(ctx: Context, attrs: AttributeSet) : View(ctx, attrs) {
    private val TAG = "VideoProgressBar"
    private val CIRCLE_LINE_WIDTH = 10
    // 动画持续时间
    private val ANI_DURATION = 350L
    // 放大倍数
    private val ZOOM_IN = 1.3f

    private var btnPaint: Paint = Paint()
    private var bgPaint: Paint = Paint()
    private var paint: Paint = Paint()
    private var rectF: RectF = RectF()

    // 按钮初始大小(按下后会变大)
    private var initSize = 0
    // 按钮完全按下后的大小
    private var maxSize = 0
    private var circleCenter = 0f

    private var btnScale = 1f
    private var progressScale = 1f
    private var progress = 0

    private var drawProgress = false
    private var listener: Listener? = null

    private var isCancel = false
    private var playingAni: AnimatorSet? = null

    init {
        btnPaint.isAntiAlias = true
        btnPaint.color = 0xFFFFFFFF.toInt()
        btnPaint.style = Paint.Style.FILL

        bgPaint.isAntiAlias = true
        bgPaint.color = 0xFFCDC9C9.toInt()
        bgPaint.style = Paint.Style.FILL

        paint.color = 0xFF00FF00.toInt()
        paint.isAntiAlias = true
        paint.strokeWidth = CIRCLE_LINE_WIDTH.toFloat()
        paint.style = Paint.Style.STROKE

        rectF.left = CIRCLE_LINE_WIDTH / 2 + .8f
        rectF.top = CIRCLE_LINE_WIDTH / 2 + .8f
    }

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val btnSize = initSize * btnScale
        val progressSize = initSize * progressScale

        rectF.right = progressSize - (CIRCLE_LINE_WIDTH / 2).toFloat() - 1.5f
        rectF.bottom = progressSize - (CIRCLE_LINE_WIDTH / 2).toFloat() - 1.5f

        canvas.drawCircle(circleCenter, circleCenter, progressSize / 2 - 1f, bgPaint)
        canvas.drawCircle(circleCenter, circleCenter, btnSize / 3 - 1f, btnPaint)
        if (drawProgress) {
            val p = progress.toFloat() / 100 * 360
            canvas.drawArc(rectF, -90f, p, false, paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (initSize == 0 || maxSize == 0) {
            initSize = MeasureSpec.getSize(widthMeasureSpec)
            maxSize = Math.ceil((initSize * ZOOM_IN).toDouble()).toInt()
            circleCenter = (maxSize / 2).toFloat()
        }
        setMeasuredDimension(maxSize, maxSize)
    }

    fun scale() {
        stopPlayingAni()
        playingAni =  AnimatorSet().apply {
            duration = ANI_DURATION
            playTogether(
                    ValueAnimator.ofFloat(progressScale, ZOOM_IN).apply {
                        addUpdateListener(progressListener)
                    },
                    ValueAnimator.ofFloat(btnScale, 0.5f).apply {
                        addUpdateListener(btnListener)
                    })
            addListener(pressedListener)
        }
        playingAni?.start()
    }

    fun revert() {
        stopPlayingAni()
        playingAni = AnimatorSet().apply {
            duration = ANI_DURATION
            playTogether(
                    ValueAnimator.ofFloat(progressScale, 1f).apply {
                        addUpdateListener(progressListener)
                    },
                    ValueAnimator.ofFloat(btnScale, 1f).apply {
                        addUpdateListener(btnListener)
                    })
            addListener(releaseListener)
        }
        playingAni?.start()
    }

    private fun stopPlayingAni() {
        playingAni?.let { ani ->
            if (ani.isRunning) {
                ani.cancel()
            }
        }
        playingAni = null
    }

    private val pressedListener = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator?) {
            super.onAnimationStart(animation)
            listener?.onRecorderBtnTouched()
            drawProgress = false
            isCancel = false
        }

        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            if (isCancel) {
                return
            }
            drawProgress = true
            listener?.onRecorderBtnPressed()
        }

        override fun onAnimationCancel(animation: Animator?) {
            super.onAnimationCancel(animation)
            isCancel = true
        }
    }

    private val releaseListener = object : AnimatorListenerAdapter() {
        override fun onAnimationStart(animation: Animator?) {
            super.onAnimationStart(animation)
            drawProgress = false
            isCancel = false
        }

        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            listener?.onRecorderBtnUp()
        }

        override fun onAnimationCancel(animation: Animator?) {
            super.onAnimationCancel(animation)
            isCancel = true
        }
    }

    private val progressListener = ValueAnimator.AnimatorUpdateListener { animation ->
        //            Log.d(TAG, "progressScale: " + progressScale);
        progressScale = animation.animatedValue as Float
        invalidate()
    }

    private val btnListener = ValueAnimator.AnimatorUpdateListener { animation ->
        //            Log.d(TAG, "btnScale: " + btnScale);
        btnScale = animation.animatedValue as Float
        invalidate()
    }

    fun setProgress(progress: Int) {
        this.progress = progress
        invalidate()
    }

    interface Listener {
        fun onRecorderBtnTouched()

        fun onRecorderBtnPressed()

        fun onRecorderBtnUp()
    }
}