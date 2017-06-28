package com.kanade.recorder

import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import kotlinx.android.synthetic.main.activity_recorder.*
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import com.kanade.recorder.Utils.CameraManager
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.initProfile

class Recorder1 : Recorder(), SurfaceHolder.Callback, MediaRecorderManager.MediaStateListener {
    private var camera: Camera? = null
    private lateinit var cameraManager: CameraManager

    private val profile: CamcorderProfile by lazy {
        val size = cameraManager.getVideoSize()
        initProfile(size.first, size.second)
    }

    override fun initRecorder() {
        super.initRecorder()
        mediaRecorderManager.setListener(this)

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)

        val holder = recorder_vv.holder
        cameraManager = CameraManager()
        cameraManager.init(holder, dm.widthPixels, dm.heightPixels)
        holder.addCallback(this)
        startPreview()
    }

    override fun onStop() {
        closeCamera()
        mediaRecorderManager.releaseMediaRecorder()
        super.onStop()
    }

    override fun focus(x: Float, y: Float) {
        cameraManager.FocusMetering(x, y)
    }

    override fun startPreview() {
        cameraManager.startPreview()
    }

    override fun zoom(zoom: Int) {
        cameraManager.zoom(zoom)
    }

    override fun zoom(isZoom: Boolean) {
        cameraManager.zoom(isZoom)
    }

    override fun touched() {
        super.touched()
        if (mediaRecorderManager.prepareRecord(profile, filePath)) {
            mediaRecorderManager.startRecord()
        }
    }

    override fun closeCamera() {
        cameraManager.releaseCamera()

    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cameraManager.init(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        closeCamera()
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