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

class VideoProgressBtn(ctx: Context, attrs: AttributeSet) : View(ctx, attrs) {
    private val TAG = "VideoProgressBar"
    private val CIRCLE_LINE_WIDTH = 10
    // 动画持续时间
    private val ANI_DURATION = 350
    // 放大倍数
    private val ZOOM_IN = 1.3f

    private var btnPaint: Paint = Paint()
    private var bgPaint: Paint = Paint()
    private var paint: Paint = Paint()
    private var rectF: RectF = RectF()

    private var initSize = 0
    private var maxSize = 0
    private var circleCenter = 0

    private var isAni = false
    private var btnScale = 1f
    private var progressScale = 1f
    private var progress = 0

    private var listener: AniEndListener? = null

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

    fun setListener(listener: AniEndListener) {
        this.listener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val btnSize = initSize * btnScale
        val progressSize = initSize * progressScale

        rectF.right = progressSize - (CIRCLE_LINE_WIDTH / 2).toFloat() - 1.5f
        rectF.bottom = progressSize - (CIRCLE_LINE_WIDTH / 2).toFloat() - 1.5f

        val p = progress.toFloat() / 100 * 360
        canvas.drawCircle(circleCenter.toFloat(), circleCenter.toFloat(), progressSize / 2 - 1f, bgPaint)
        canvas.drawCircle(circleCenter.toFloat(), circleCenter.toFloat(), btnSize / 3 - 1f, btnPaint)
        if (!isAni) canvas.drawArc(rectF, -90f, p, false, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (initSize == 0 || maxSize == 0) {
            initSize = MeasureSpec.getSize(widthMeasureSpec)
            maxSize = Math.ceil((initSize * ZOOM_IN).toDouble()).toInt()
            circleCenter = maxSize / 2
        }
        setMeasuredDimension(maxSize, maxSize)
    }

    fun startRecord() {
        val progressAni = ValueAnimator.ofFloat(progressScale, ZOOM_IN)
        val btnAni = ValueAnimator.ofFloat(btnScale, 0.5f)
        progressAni.addUpdateListener(progressListener)
        btnAni.addUpdateListener(btnListener)
        val set = AnimatorSet()
        set.playTogether(progressAni, btnAni)
        set.addListener(touchedAdapter)
        set.duration = ANI_DURATION.toLong()
        isAni = true
        set.start()
    }

    fun recordComplete() {
        val progressAni = ValueAnimator.ofFloat(progressScale, 1f)
        val btnAni = ValueAnimator.ofFloat(btnScale, 1f)
        progressAni.addUpdateListener(progressListener)
        btnAni.addUpdateListener(btnListener)
        val set = AnimatorSet()
        set.playTogether(progressAni, btnAni)
        set.addListener(untouchedAdapter)
        set.duration = ANI_DURATION.toLong()
        isAni = true
        set.start()
    }

    private val touchedAdapter = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            isAni = false
            listener?.touched()
        }
    }

    private val untouchedAdapter = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            super.onAnimationEnd(animation)
            isAni = false
            listener?.untouched()
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

    interface AniEndListener {
        fun touched()

        fun untouched()
    }
}