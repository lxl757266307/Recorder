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
import android.view.MotionEvent
import android.view.View

class VideoProgressBtn(ctx: Context, attrs: AttributeSet) : View(ctx, attrs), View.OnTouchListener {
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

    private val pressedAni by lazy {
        val progressAni = ValueAnimator.ofFloat(1f, ZOOM_IN)
        val btnAni = ValueAnimator.ofFloat(1f, 0.5f)
        progressAni.addUpdateListener(progressListener)
        btnAni.addUpdateListener(btnListener)
        AnimatorSet().apply {
            playTogether(progressAni, btnAni)
            addListener(pressedListener)
            duration = ANI_DURATION
        }
    }

    private val releaseAni by lazy {
        val progressAni = ValueAnimator.ofFloat(ZOOM_IN, 1f)
        val btnAni = ValueAnimator.ofFloat(0.5f, 1f)
        progressAni.addUpdateListener(progressListener)
        btnAni.addUpdateListener(btnListener)
        AnimatorSet().apply {
            playTogether(progressAni, btnAni)
            addListener(releaseListener)
            duration = ANI_DURATION
        }
    }

    private var listener: Listener? = null

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

        val p = progress.toFloat() / 100 * 360
        canvas.drawCircle(circleCenter, circleCenter, progressSize / 2 - 1f, bgPaint)
        canvas.drawCircle(circleCenter, circleCenter, btnSize / 3 - 1f, btnPaint)
        if (!pressedAni.isRunning && !releaseAni.isRunning) {
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

    private val pressedListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            listener?.onPressed()
        }

        override fun onAnimationCancel(animation: Animator?) {
            super.onAnimationCancel(animation)
            btnScale = 1f
            progressScale = 1f
        }
    }

    private val releaseListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            listener?.onRelease()
        }

        override fun onAnimationCancel(animation: Animator?) {
            super.onAnimationCancel(animation)
            btnScale = 1f
            progressScale = 1f
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

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (releaseAni.isRunning) {
                    releaseAni.cancel()
                }
                pressedAni.start()
            }
            MotionEvent.ACTION_UP -> {
                if (pressedAni.isRunning) {
                    pressedAni.cancel()
                }
                releaseAni.start()
            }
        }
        return true
    }

    interface Listener {
        fun onPressed()

        fun onRelease()
    }
}