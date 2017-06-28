package com.kanade.recorder

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
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
    private var mRecordBuilder: CaptureRequest.Builder? = null
    private var mPreviewSession: CameraCaptureSession? = null

    private val initSize: RecorderSize by lazy {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        RecorderSize(dm.heightPixels, dm.widthPixels)
    }
    private lateinit var optimalSize: RecorderSize

    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler
    // 当前缩放值
    private var curZoom = 1f

    private val mCameraOpenCloseLock = Semaphore(1)

    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
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
                                Toast.makeText(this@Recorder2, getString(R.string.start_preview_failed), Toast.LENGTH_SHORT).show()
                            }
                        }, mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
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
        startPreview()
    }

    override fun recordComplete() {
        if (isRecording) {
            mediaRecorderManager.stopRecord()
            isRecording = false
            val sec = duration / 10.0
            if (sec < 1) {
                Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_LONG).show()
                closeCamera()
                startPreview()
            } else {
                closeCamera()
                recorder_progress.recordComplete()
                // 隐藏"录像"和"返回"按钮，显示"取消"和"确认"按钮，并播放已录制的视频
                startShowCompleteAni()
                playVideo(filePath)
            }
        }
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
        startRecord(mCameraDevice)
    }

    override fun startPreview() {
        super.startPreview()
        val holder = recorder_vv.holder
        startPreview(holder, initSize.width, initSize.height)
    }

    override fun zoom(zoom: Float) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        val characteristics = manager.getCameraCharacteristics(cameraId)

        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).toInt()
        val z = Math.min(maxZoom.toFloat(), zoom)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorRect?.let { rect ->
            var left = rect.width() / 2
            var right = left
            var top = rect.height() / 2
            var bottom = top
            val hwidth = (rect.width() / (2.0 * zoom)).toInt()
            val hheight = (rect.height() / (2.0 * zoom)).toInt()

            left -= hwidth
            right += hwidth
            top -= hheight
            bottom += hheight

            val newRect = Rect(left, top, right, bottom)
            mPreviewBuilder?.set(CaptureRequest.SCALER_CROP_REGION, newRect)
            updatePreview()
            curZoom = z
        }
    }

    override fun zoom(zoomIn: Boolean) {
        super.zoom(zoomIn)
        if (zoomIn) {
            Log.d(TAG, "zoom in")
            curZoom += 0.05f
        } else {
            if (curZoom > 1) {
                curZoom -= 0.1f
            }
            Log.d(TAG, "zoom out")
        }
        zoom(curZoom)
    }

    override fun focus(x: Float, y: Float) {
        super.focus(x, y)
    }

    private fun startPreview(holder: SurfaceHolder, width: Int, height: Int) {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(MediaRecorder::class.java)
                    .map { RecorderSize(it.height, it.width) }
            val screenProp = height / width.toFloat()
            optimalSize = getBestSize(map, 1000, screenProp)

            holder.setFixedSize(optimalSize.width, optimalSize.height)
            holder.setKeepScreenOn(true)
            manager.openCamera(cameraId, mStateCallback, null)
        } catch (e: CameraAccessException) {
            Toast.makeText(this, R.string.start_preview_failed, Toast.LENGTH_SHORT).show()
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

    private fun startRecord(device: CameraDevice) {
        closePreviewSession()
        try {
            val preBuilder = mPreviewBuilder
            mPreviewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mediaRecorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filePath)

            val surface = recorder_vv.holder.surface

            val surfaces = ArrayList<Surface>()
            surfaces.add(surface)
            mPreviewBuilder?.addTarget(surface)

            mediaRecorderManager.getRecorder()?.surface?.let {
                surfaces.add(it)
                mPreviewBuilder?.addTarget(it)
                mPreviewBuilder?.set(CaptureRequest.SCALER_CROP_REGION, preBuilder?.get(CaptureRequest.SCALER_CROP_REGION))
                runOnUiThread { mediaRecorderManager.startRecord() }
            }

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
                    Toast.makeText(this@Recorder2, getString(R.string.start_record_failed), Toast.LENGTH_SHORT).show()
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