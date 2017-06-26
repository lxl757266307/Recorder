package com.kanade.recorder

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import com.kanade.recorder.R.id.recorder_tv
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize
import com.kanade.recorder.Utils.initProfile
import kotlinx.android.synthetic.main.activity_recorder2.*
import permissions.dispatcher.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RuntimePermissions
class Recorder2 : BaseActivity() {
    private val TAG = "Recorder2"
    private val MAX_DURATION = 10

    companion object {
        private const val RESULT_FILEPATH = "result_filepath"
        private const val ARG_FILEPATH = "arg_filepath"
        @JvmStatic
        fun newIntent(ctx: Context, filePath: String): Intent =
                Intent(ctx, Recorder2::class.java)
                        .putExtra(ARG_FILEPATH, filePath)

        @JvmStatic
        fun getResult(intent: Intent): RecorderResult =
                intent.getParcelableExtra(RESULT_FILEPATH)
    }

    private lateinit var filePath: String
    private var duration = 0f

    private var mCameraDevice: CameraDevice? = null
    private var mPreviewBuilder: CaptureRequest.Builder? = null
    private var mPreviewSession: CameraCaptureSession? = null

    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) = openCamera(width, height)
        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = configureTransform(width, height)
        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    private val initSize: RecorderSize by lazy {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        RecorderSize(dm.heightPixels, dm.widthPixels)
    }
    private lateinit var optimalSize: RecorderSize
    private lateinit var mediaRecorderManager: MediaRecorderManager

    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val mCameraOpenCloseLock = Semaphore(1)

    private val mStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            startPreview()
            mCameraOpenCloseLock.release()
            configureTransform(initSize.width, initSize.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder2)
        filePath = intent.getStringExtra(ARG_FILEPATH)
        mediaRecorderManager = MediaRecorderManager()

        BaseActivityPermissionsDispatcher.checkPermissionWithCheck(this)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (recorder_vv.isAvailable) {
            openCamera(recorder_vv.width, recorder_vv.height)
        } else {
            recorder_vv.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onStop() {
        closeCamera()
        stopBackgroundThread()
        super.onStop()
    }

    override fun touched() {
        super.touched()
        if (!recorder_vv.isAttachedToWindow) {
            return
        }
        mCameraDevice?.let { initCameraDevice(it) }
    }

    override fun untouched() {
        super.untouched()
        startPreview()
        recordComplete()
    }

    override fun initRunnable(): Runnable {
        duration++
        val sec = duration / 10.0
        recorder_progress.setProgress((sec / MAX_DURATION * 100).toInt())
        if (sec > MAX_DURATION) {
            recordComplete()
        }
        return super.initRunnable()
    }

    private fun recordComplete() {
        if (isRecording) {
            isRecording = false
            mediaRecorderManager.stopRecord()

        }
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread.start()
        mBackgroundHandler = Handler(mBackgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThread.quitSafely()
        try {
            mBackgroundThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /**
     * Tries to open a [CameraDevice]. The result is listened by `mStateCallback`.
     */
    private fun openCamera(width: Int, height: Int) {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[0]

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceTexture::class.java)
                    .map { RecorderSize(it.height, it.width) }

            val screenProp = height / width.toFloat()
            optimalSize = getBestSize(map, 1000, screenProp)

            recorder_vv.setAspectRatio(optimalSize.height, optimalSize.width)
            configureTransform(width, height)
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
            mCameraDevice?.close()
            mCameraDevice = null
            mediaRecorderManager.releaseMediaRecorder()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    private fun startPreview() {
        if (!recorder_tv.isAvailable) {
            return
        }
        try {
            closePreviewSession()
            val texture = recorder_tv.surfaceTexture
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)
            mPreviewBuilder = mCameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            mPreviewBuilder?.addTarget(previewSurface)

            mCameraDevice?.createCaptureSession(listOf(previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            mPreviewSession = session
                            updatePreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) { Toast.makeText(this@Recorder2, "Failed", Toast.LENGTH_SHORT).show() }
                    }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    /**
     * Update the camera preview. [.startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (null == mCameraDevice) {
            return
        }
        mPreviewBuilder?.let { builder ->
            try {
                setUpCaptureRequestBuilder(builder)
                mPreviewSession?.setRepeatingRequest(builder.build(), null, mBackgroundHandler)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
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
            val scale = Math.max(viewHeight.toFloat() / optimalSize.height,
                    viewWidth.toFloat() / optimalSize.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        recorder_tv.setTransform(matrix)
    }

    private fun initCameraDevice(device: CameraDevice) {
        try {
            closePreviewSession()
            mediaRecorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filePath)
            val texture = recorder_tv.surfaceTexture
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)
            mPreviewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

            val surfaces = ArrayList<Surface>()

            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            mPreviewBuilder?.addTarget(previewSurface)

            mediaRecorderManager.getSurface()?.let { surface ->
                surfaces.add(surface)
                mPreviewBuilder?.addTarget(surface)
                runOnUiThread { mediaRecorderManager.startRecord() }
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            isRecording = true
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
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