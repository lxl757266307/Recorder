package com.kanade.recorder

import android.content.Context
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.view.SurfaceHolder
import com.kanade.recorder.Utils.CameraManager
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.initProfile
import com.kanade.recorder._interface.IRecorderContract
import java.io.File

class RecorderPresenter : IRecorderContract.Presenter, SurfaceHolder.Callback, MediaRecorderManager.MediaStateListener {
    private val TAG = "CameraPresenter"
    private val MAX_DURATION = 10
    private var duration = 0f
    private var isRecording = false
    private var camera: Camera? = null
    private lateinit var filePath: String
    private lateinit var view: IRecorderContract.View
    private lateinit var cameraManager: CameraManager
    private lateinit var mediaRecorderManager: MediaRecorderManager

    private val profile: CamcorderProfile by lazy {
        val size = cameraManager.getVideoSize()
        initProfile(size.first, size.second)
    }

    override fun attach(v: IRecorderContract.View, filePath: String) {
        this.view = v
        this.filePath = filePath
        this.mediaRecorderManager = MediaRecorderManager()
    }

    override fun detach() {
        view.stopVideo()
        mediaRecorderManager.releaseMediaRecorder()
    }

    override fun init(holder: SurfaceHolder, width: Int, height: Int) {
        cameraManager = CameraManager()
        cameraManager.init(holder, width, height)
        mediaRecorderManager.setListener(this)
        holder.addCallback(this)
    }

    override fun startPreview() {
        cameraManager.connectCamera()
    }

    override fun handleFocusMetering(x: Float, y: Float) = cameraManager.handleFocusMetering(x, y)

    override fun handleZoom(isZoom: Boolean) = cameraManager.handleZoom(isZoom)

    override fun handleZoom(zoom: Int) = cameraManager.handleZoom(zoom)

    override fun reconnect() {
        view.stopVideo()
        deleteFile()
        startPreview()
    }

    override fun staretRecord() {
        duration = 0f
        isRecording = true
        if (mediaRecorderManager.prepareRecord(profile, filePath)) {
            mediaRecorderManager.startRecord()
        }
    }

    override fun recording() {
        duration++
        val sec = duration / 10.0
        view.updateProgress((sec / MAX_DURATION * 100).toInt())
        if (sec > MAX_DURATION)
            recordComplete()
    }

    override fun recordComplete() {
        if (isRecording) {
            isRecording = false
            mediaRecorderManager.stopRecord()
            val sec = duration / 10.0
            if (sec < 1) {
                view.notice(getContext().getString(R.string.record_too_short))
            } else {
                cameraManager.releaseCamera()
                view.recordComplete()
                view.playVideo(filePath)
            }
        }
    }

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

    override fun setResult() {
        val result = RecorderResult(filePath, (duration / 10.0).toInt())
        view.setResult(result)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        cameraManager.init(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        cameraManager.releaseCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    private fun deleteFile() {
        File(filePath).run { if (exists()) delete() }
    }

    override fun getContext(): Context = view.Context()
}