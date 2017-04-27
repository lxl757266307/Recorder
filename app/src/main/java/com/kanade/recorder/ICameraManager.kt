package com.kanade.recorder

import android.view.SurfaceHolder

interface ICameraManager {
    fun getVideoSize(): Pair<Int, Int>

    fun init(holder: SurfaceHolder)

    fun init(holder: SurfaceHolder, width: Int, height: Int)

    fun connectCamera()

    fun handleFocusMetering(x: Float, y: Float)

    fun handleZoom(isZoomIn: Boolean)

    fun releaseCamera()
}