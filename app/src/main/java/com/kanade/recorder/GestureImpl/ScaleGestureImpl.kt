package com.kanade.recorder.GestureImpl

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class ScaleGestureImpl(ctx: Context, var listener: GestureListener): ScaleGestureDetector.SimpleOnScaleGestureListener() {
    private val scaleGesture = ScaleGestureDetector(ctx, this)

    fun onTouched(event: MotionEvent): Boolean {
        scaleGesture.onTouchEvent(event)
        return processTouchEvent(event)
    }

    @SuppressLint("Recycle")
    private fun processTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount <= 1 && event.action == MotionEvent.ACTION_UP) {
            listener.onSingleTap(event)
        }
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val scaleFactor = detector.scaleFactor
        if (java.lang.Float.isNaN(scaleFactor) || java.lang.Float.isInfinite(scaleFactor)) return false
        listener.onScale(scaleFactor, detector.focusX, detector.focusY)
        return true
    }

    interface GestureListener {
        fun onSingleTap(event: MotionEvent)

        fun onScale(scaleFactor: Float, focusX: Float, focusY: Float)
    }
}