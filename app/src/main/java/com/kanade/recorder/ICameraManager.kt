package com.kanade.recorder

import android.view.SurfaceHolder

interface ICameraManager {
    fun getVideoSize(): Pair<Int, Int>

    fun initCamera(holder: SurfaceHolder, width: Int, height: Int)

    fun handleFocusMetering(x: Float, y: Float)

    fun handleZoom(isZoomIn: Boolean)

    fun reconnect(holder: SurfaceHolder, width: Int, height: Int)

    fun releaseCamera()
}