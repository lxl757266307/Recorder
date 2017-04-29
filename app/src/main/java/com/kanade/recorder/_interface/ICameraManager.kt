package com.kanade.recorder._interface

import android.view.SurfaceHolder

interface ICameraManager {
    fun getVideoSize(): Pair<Int, Int>

    fun init(holder: android.view.SurfaceHolder)

    fun init(holder: android.view.SurfaceHolder, width: Int, height: Int)

    fun connectCamera()

    fun handleFocusMetering(x: Float, y: Float)

    fun handleZoom(isZoomIn: Boolean)

    fun handleZoom(zoom: Int)

    fun releaseCamera()
}