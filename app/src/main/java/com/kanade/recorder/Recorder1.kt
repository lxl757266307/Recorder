package com.kanade.recorder

import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import kotlinx.android.synthetic.main.activity_recorder.*
import android.util.DisplayMetrics
import android.view.SurfaceHolder
import android.widget.Toast
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
        cameraManager.releaseCamera()
        mediaRecorderManager.releaseMediaRecorder()
        super.onStop()
    }

    override fun focus(x: Float, y: Float) {
        cameraManager.FocusMetering(x, y)
    }

    override fun startPreview() {
        cameraManager.startPreview()
    }

    override fun zoom(zoom: Float) {
        cameraManager.zoom(zoom.toInt())
    }

    override fun zoom(zoomIn: Boolean) {
        cameraManager.zoom(zoomIn)
    }

    override fun touched() {
        super.touched()
        if (mediaRecorderManager.prepareRecord(profile, filePath)) {
            mediaRecorderManager.startRecord()
        }
    }

    override fun recordComplete() {
        if (isRecording) {
            mediaRecorderManager.stopRecord()
            isRecording = false
            val sec = duration / 10.0
            if (sec < 1) {
                Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_LONG).show()
            } else {
                cameraManager.releaseCamera()
                recorder_progress.recordComplete()
                // 隐藏"录像"和"返回"按钮，显示"取消"和"确认"按钮，并播放已录制的视频
                startShowCompleteAni()
                playVideo(filePath)
            }
        }
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