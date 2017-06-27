package com.kanade.recorder

import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import kotlinx.android.synthetic.main.activity_recorder.*
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import com.kanade.recorder.Utils.CameraManager
import com.kanade.recorder.Utils.initProfile

class Recorder1 : Recorder(), SurfaceHolder.Callback {
    private val TAG = "Recorder1"

    private var camera: Camera? = null
    private lateinit var cameraManager: CameraManager

    private val profile: CamcorderProfile by lazy {
        val size = cameraManager.getVideoSize()
        initProfile(size.first, size.second)
    }

    override fun checkPermission() {
        super.checkPermission()
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)

        val holder = recorder_vv.holder
        cameraManager = CameraManager()
        cameraManager.init(holder, dm.widthPixels, dm.heightPixels)
        holder.addCallback(this)
        startPreview()
    }

    override fun focus(x: Float, y: Float) {
        cameraManager.handleFocusMetering(x, y)
    }

    override fun startPreview() {
        cameraManager.connectCamera()

    }

    override fun zoom(zoom: Int) {
        cameraManager.handleZoom(zoom)
    }

    override fun zoom(isZoom: Boolean) {
        cameraManager.handleZoom(isZoom)
    }

    override fun touched() {
        super.touched()
        if (mediaRecorderManager.prepareRecord(profile, filePath)) {
            mediaRecorderManager.startRecord()
        }
    }

    override fun recordComplete() {
        cameraManager.releaseCamera()
        super.recordComplete()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cameraManager.init(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        cameraManager.releaseCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder?) = Unit

    override fun prepare(recorder: MediaRecorder) {
        camera = cameraManager.getCamera()
        camera?.let { camera ->
            camera.unlock()
            recorder.setCamera(camera)
        }
    }

    override fun release(recorder: MediaRecorder) {
        camera?.lock()
        camera = null
    }
}