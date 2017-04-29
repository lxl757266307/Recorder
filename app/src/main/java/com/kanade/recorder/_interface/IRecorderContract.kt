package com.kanade.recorder._interface

import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import com.kanade.recorder.RecorderResult

interface IRecorderContract {
    interface View {
        fun getContext(): Context

        fun playVideo(filePath: String)

        fun stopVideo()

        fun updateProgress(p: Int)

        fun recordComplete()

        fun setResult(result: RecorderResult)
    }

    interface Presenter {
        fun attach(v: View, filePath: String)

        fun detach()

        fun init(holder: SurfaceHolder, width: Int, height: Int)

        fun startPreview()

        fun staretRecord()

        fun recording()

        fun recordComplete()

        fun handleFocusMetering(x: Float, y: Float)

        fun handleZoom(isZoom: Boolean)

        fun reconnect()

        fun setResult()
    }
}