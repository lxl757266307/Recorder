package com.kanade.recorder.camera1

import android.hardware.Camera
import android.util.Log
import android.view.SurfaceHolder
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize

@Suppress("DEPRECATION")
class CameraManager(private var holder: SurfaceHolder,
                    private var initWidth: Int,
                    private var initHeight: Int) : Camera.AutoFocusCallback {
    private val TAG = "CameraManager"

    private lateinit var params: Camera.Parameters
    private lateinit var camera: Camera

    private var lastPosi = 0
    private var isRelease = true
    private var isPreview = false
    private var svWidth: Int = 0
    private var svHeight: Int = 0

    fun updateHolder(holder: SurfaceHolder) {
        this.holder = holder
        if (isPreview) {
            camera.setPreviewDisplay(holder)
        }
    }

    fun startPreview() {
        if (isPreview) {
            return
        }
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

    fun openFlash() {
        params.flashMode = Camera.Parameters.FLASH_MODE_TORCH
        try {
            camera.parameters = params
        } catch (e: Exception) {
            Log.d(TAG, "open flash error")
            e.printStackTrace()
        }
    }

    fun closeFlash() {
        params.flashMode = Camera.Parameters.FLASH_MODE_OFF
        try {
            camera.parameters = params
        } catch (e: Exception) {
            Log.d(TAG, "open flash error")
            e.printStackTrace()
        }
    }

    fun releaseCamera() {
        if (!isRelease) {
            closeFlash()
            camera.unlock()
            camera.stopPreview()
            camera.release()
            isRelease = true
            isPreview = false
        }
    }

    override fun onAutoFocus(success: Boolean, camera: Camera) {
        if (success) {
            camera.cancelAutoFocus()
        }
    }

    /**
     * @return first => width, second => height
     */
    fun getVideoSize(): Pair<Int, Int> = Pair(svWidth, svHeight)

    /**
     * 缩放
     * @param zoomIn
     */
    @Synchronized
    fun zoom(zoomIn: Boolean): Int {
        if (!isPreview || isRelease || !params.isZoomSupported) {
            return -1
        }
        val maxZoom = params.maxZoom
        val ratios = params.zoomRatios
        if (zoomIn) {
            lastPosi++
        } else {
            lastPosi -= 2
        }
        if (lastPosi > maxZoom) {
            lastPosi = maxZoom
        } else if (lastPosi < 0) {
            lastPosi = 0
        }

        params.zoom = lastPosi

        try {
            camera.parameters = params
        } catch (e: Exception) {
            Log.d(TAG, "zoom error")
            e.printStackTrace()
        }
        return ratios[lastPosi]
    }

    private fun setParams(holder: SurfaceHolder, params: Camera.Parameters, width: Int, height: Int) {
        // 获取最合适的视频尺寸(预览和录像)
        val supportPreviewSizes = params.supportedPreviewSizes.map { RecorderSize(it.height, it.width) }
        val screenProp = height / width.toFloat()
        val optimalSize = getBestSize(supportPreviewSizes, 1000, screenProp)
        this.svWidth = optimalSize.width
        this.svHeight = optimalSize.height

        holder.setFixedSize(svWidth, svHeight)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        params.zoom = 0
        params.setPreviewSize(svWidth, svHeight)
        params.setPictureSize(svWidth, svHeight)
        val supportFocusModes = params.supportedFocusModes
        supportFocusModes.indices
                .filter { supportFocusModes[it] == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO }
                .forEach { params.focusMode = supportFocusModes[it] }
    }
}