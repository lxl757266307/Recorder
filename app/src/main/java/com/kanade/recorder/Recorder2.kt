package com.kanade.recorder

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize
import com.kanade.recorder.Utils.initProfile
import kotlinx.android.synthetic.main.activity_recorder.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class Recorder2 : Recorder() {
    private lateinit var mCameraDevice: CameraDevice
    private var mPreviewBuilder: CaptureRequest.Builder? = null
    private var mPreviewSession: CameraCaptureSession? = null

    private val initSize: RecorderSize by lazy {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        RecorderSize(dm.heightPixels, dm.widthPixels)
    }
    private lateinit var optimalSize: RecorderSize

    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler

    private val mCameraOpenCloseLock = Semaphore(1)

    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            initPreview()
            mCameraOpenCloseLock.release()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread.start()
        mBackgroundHandler = Handler(mBackgroundThread.looper)
    }

    override fun initRecorder() {
        super.initRecorder()
        val holder = recorder_vv.holder
        openCamera(holder, initSize.width, initSize.height)
    }

    override fun onStop() {
        closeCamera()
        mBackgroundThread.quitSafely()
        try {
            mBackgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        super.onStop()
    }

    override fun touched() {
        super.touched()
        initCameraDevice(mCameraDevice)
    }

    override fun recordComplete() {
        mediaRecorderManager.stopRecord()
        val sec = duration / 10.0
        if (sec < 1) {
            Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_LONG).show()
        } else {
            closeCamera()
            recorder_progress.recordComplete()
            // 隐藏"录像"和"返回"按钮，显示"取消"和"确认"按钮，并播放已录制的视频
            startShowCompleteAni()
            playVideo(filePath)
        }
    }

    override fun startPreview() {
        super.startPreview()
        val holder = recorder_vv.holder
        openCamera(holder, initSize.width, initSize.height)
    }

    private fun openCamera(holder: SurfaceHolder, width: Int, height: Int) {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceTexture::class.java)
                    .map { RecorderSize(it.height, it.width) }
            val screenProp = height / width.toFloat()
            optimalSize = getBestSize(map, 1000, screenProp)

            holder.setFixedSize(optimalSize.width, optimalSize.height)
            manager.openCamera(cameraId, mStateCallback, null)
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Cannot access the camera.", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: NullPointerException) {
            showErrorDialog()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            closePreviewSession()
            mCameraDevice.close()
            mediaRecorderManager.releaseMediaRecorder()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    private fun initPreview() {
        closePreviewSession()
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val surface = recorder_vv.holder.surface
            mPreviewBuilder?.addTarget(surface)

            mCameraDevice.createCaptureSession(listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            mPreviewSession = session
                            updatePreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(this@Recorder2, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        mPreviewBuilder?.let { builder ->
            try {
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                mPreviewSession?.setRepeatingRequest(builder.build(), null, mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun initCameraDevice(device: CameraDevice) {
        closePreviewSession()
        try {
            mPreviewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mediaRecorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filePath)
            val surface = recorder_vv.holder.surface

            val surfaces = ArrayList<Surface>()
            surfaces.add(surface)
            mPreviewBuilder?.addTarget(surface)

            mediaRecorderManager.getSurface()?.let {
                surfaces.add(it)
                mPreviewBuilder?.addTarget(it)
                runOnUiThread { mediaRecorderManager.startRecord() }
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onReady(session: CameraCaptureSession?) {
                    super.onReady(session)
                    isRecording = true
                }

                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession
                    updatePreview()
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(this@Recorder2, "Failed", Toast.LENGTH_SHORT).show()
                }
            }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        mPreviewSession?.close()
    }

    private fun showErrorDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.camera_error)
                .setPositiveButton(R.string.button_allow, { d, _ -> d.dismiss(); finish() })
                .show()
    }
}