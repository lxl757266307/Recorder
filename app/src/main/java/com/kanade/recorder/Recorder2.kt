package com.kanade.recorder

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.*
import android.widget.Toast
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize
import com.kanade.recorder.Utils.initProfile
import com.kanade.recorder.widget.AutoFitTextureView
import kotlinx.android.synthetic.main.activity_recorder.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class Recorder2 : Recorder() {
    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mCameraBuilder: CaptureRequest.Builder
    private var mCameraSession: CameraCaptureSession? = null

    private lateinit var optimalSize: RecorderSize

    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler
    private lateinit var textureView: AutoFitTextureView
    // 当前缩放值
    private var curZoom = 1f

    private val mCameraOpenCloseLock = Semaphore(1)

    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }
        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            startPreview()
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

    override fun startPreview() {
        closePreviewSession()
        try {
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)

            val surface = Surface(texture)
            mCameraBuilder.addTarget(surface)
            mCameraBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)
            mCameraBuilder.set(CaptureRequest.JPEG_QUALITY, 90)

            mCameraDevice.createCaptureSession(listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            mCameraSession = session
                            updatePreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(this@Recorder2, getString(R.string.start_preview_failed), Toast.LENGTH_SHORT).show()
                        }
                    }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread.start()
        mBackgroundHandler = Handler(mBackgroundThread.looper)
    }

    override fun init(surfaceView: View) {
        textureView = surfaceView as AutoFitTextureView
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun startRecord() {
        closePreviewSession()
        try {
            val preBuilder = mCameraBuilder
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mediaRecorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filePath)

            val texture = textureView.surfaceTexture
            val surface = Surface(texture)

            val surfaces = ArrayList<Surface>()
            surfaces.add(surface)
            mCameraBuilder.addTarget(surface)

            mediaRecorderManager.getRecorder()?.surface?.let {
                surfaces.add(it)
                mCameraBuilder.addTarget(it)
                mCameraBuilder.set(CaptureRequest.SCALER_CROP_REGION, preBuilder.get(CaptureRequest.SCALER_CROP_REGION))
                runOnUiThread {
                    isRecording = true
                    mediaRecorderManager.startRecord()
                }
            }

            mCameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    mCameraSession = cameraCaptureSession
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

    override fun playVideo(filePath: String) {
        textureView.setVideoPath(File(filePath))
        textureView.start()
    }

    override fun stopPlay() {
        textureView.stopPlayback()
    }

    override fun cancelBtnClick() {
        stopPlay()
        startPreview()
    }

    override fun release() {
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

    override fun recordComplete() {
        if (isRecording) {
            mediaRecorderManager.stopRecord()
            closePreviewSession()
            isRecording = false
            val sec = duration / 10.0
            if (sec < 1) {
                Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_LONG).show()
                startPreview()
            } else {
                recorder_progress.recordComplete()
                // 隐藏"录像"和"返回"按钮，显示"取消"和"确认"按钮，并播放已录制的视频
                startShowCompleteAni()
                playVideo(filePath)
            }
        }
    }

    override fun onStop() {
        mBackgroundThread.quitSafely()
        try {
            mBackgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        super.onStop()
    }

    override fun zoom(zoomIn: Boolean) {
        if (zoomIn) {
            curZoom += 0.05f
        } else {
            if (curZoom > 1) {
                curZoom -= 0.1f
            }
        }
        zoom(curZoom)
    }

    private fun zoom(zoom: Float) {
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
            mCameraBuilder.set(CaptureRequest.SCALER_CROP_REGION, newRect)
            updatePreview()
            curZoom = z
        }
    }

    override fun focus(x: Float, y: Float) {
    }

    private fun openCamera(width: Int, height: Int) {
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

            textureView.setAspectRatio(optimalSize.height, optimalSize.width)
            configureTransform(optimalSize.width, optimalSize.height)
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

    private fun updatePreview() {
        try {
            mCameraBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            mCameraSession?.setRepeatingRequest(mCameraBuilder.build(), null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        mCameraSession?.close()
        mCameraSession = null
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, optimalSize.height.toFloat(), optimalSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(viewHeight.toFloat() / optimalSize.height, viewWidth.toFloat() / optimalSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    private fun showErrorDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.camera_error)
                .setPositiveButton(R.string.button_allow, { d, _ -> d.dismiss(); finish() })
                .show()
    }
}