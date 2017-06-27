package com.kanade.recorder.Utils

import android.hardware.Camera
import android.util.Log
import android.view.SurfaceHolder
import java.util.*

@Suppress("DEPRECATION")
class CameraManager : Camera.AutoFocusCallback {
    private val TAG = "CameraManager"

    private lateinit var holder: SurfaceHolder
    private lateinit var params: Camera.Parameters
    private lateinit var camera: Camera

    private var isRelease = true
    private var isPreview = false
    private var svWidth: Int = 0
    private var svHeight: Int = 0
    private var initWidth: Int = 0
    private var initHeight: Int = 0

    fun init(holder: SurfaceHolder) {
        this.holder = holder
        if (isPreview) {
            camera.setPreviewDisplay(holder)
        }
    }

    fun init(holder: SurfaceHolder, width: Int, height: Int) {
        this.initWidth = width
        this.initHeight = height
        init(holder)
    }

    fun connectCamera() {
        if (isPreview) return
        try {
            isRelease = false
            isPreview = true
            camera = Camera.open(0)
            params = camera.parameters
            setParams(holder, params, initWidth, initHeight)
            camera.parameters = params
            camera.setDisplayOrientation(90)
            camera.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
        }
    }

    fun getCamera(): Camera = camera

    fun releaseCamera() {
        if (!isRelease) {
            camera.stopPreview()
            camera.release()
            isRelease = true
            isPreview = false
            Log.d(TAG, "camera has release")
        }
    }

    override fun onAutoFocus(success: Boolean, camera: Camera) {
        if (success) camera.cancelAutoFocus()
    }

    /**
     * @return first => width, second => height
     */
    fun getVideoSize(): Pair<Int, Int> = Pair(svWidth, svHeight)

    /**
     * 对焦
     * @param x
     * *
     * @param y
     */
     @Synchronized fun handleFocusMetering(x: Float, y: Float) {
        if (!isPreview || isRelease) return
        val focusRect = calculateTapArea(x, y, svWidth, svHeight, 1f)
        val meteringRect = calculateTapArea(x, y, svWidth, svHeight, 1.5f)

        params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO

        if (params.maxNumFocusAreas > 0) {
            val focusAreas = ArrayList<Camera.Area>()
            focusAreas.add(Camera.Area(focusRect, 1000))

            params.focusAreas = focusAreas
        }

        if (params.maxNumMeteringAreas > 0) {
            val meteringAreas = ArrayList<Camera.Area>()
            meteringAreas.add(Camera.Area(meteringRect, 1000))

            params.meteringAreas = meteringAreas
        }

        try {
            camera.parameters = params
            camera.autoFocus(this)
        } catch (e: Exception) {
            Log.d(TAG, "focus error")
            e.printStackTrace()
        }
    }

    /**
     * 缩放
     * @param isZoomIn
     */
    @Synchronized fun handleZoom(isZoomIn: Boolean) {
        if (!isPreview || isRelease || !params.isZoomSupported) return
        val maxZoom = params.maxZoom
        var zoom = params.zoom
        if (isZoomIn && zoom < maxZoom) {
            zoom++
        } else if (zoom > 0) {
            zoom--
        }

        params.zoom = zoom

        try {
            camera.parameters = params
        } catch (e: Exception) {
            Log.d(TAG, "zoom error")
            e.printStackTrace()
        }
    }

    @Synchronized fun handleZoom(zoom: Int) {
        if (!isPreview || isRelease || !params.isZoomSupported) return
        val maxZoom = params.maxZoom
        params.zoom = Math.min(zoom, maxZoom)
        try {
            camera.parameters = params
        } catch (e: Exception) {
            Log.d(TAG, "zoom error")
            e.printStackTrace()
        }
    }

    private fun setParams(holder: SurfaceHolder, params: Camera.Parameters, width: Int, height: Int) {
        // 获取最合适的视频尺寸(预览和录像)
        val supportPreviewSizes = params.supportedPreviewSizes.map { RecorderSize(it.height, it.width) }
        val screenProp = height / width.toFloat()
        val optimalSize = getBestSize(supportPreviewSizes, 1000, screenProp)
        this.svWidth = optimalSize.width
        this.svHeight = optimalSize.height

        // 设置holder
        holder.setFixedSize(svWidth, svHeight)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        // 设置params
        params.zoom = 1
        params.setPreviewSize(svWidth, svHeight)
        params.setPictureSize(svWidth, svHeight)
        val supportFocusModes = params.supportedFocusModes
        supportFocusModes.indices
                .filter { supportFocusModes[it] == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO }
                .forEach { params.focusMode = supportFocusModes[it] }
    }
}