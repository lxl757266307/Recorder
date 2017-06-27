package com.kanade.recorder

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize
import com.kanade.recorder.Utils.initProfile
import kotlinx.android.synthetic.main.activity_recorder.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
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

    // 录像文件保存路径
    private lateinit var filePath: String
    private var duration = 0f

    private lateinit var mCameraDevice: CameraDevice
    private var mPreviewBuilder: CaptureRequest.Builder? = null
    private var mPreviewSession: CameraCaptureSession? = null

    private val initSize: RecorderSize by lazy {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        RecorderSize(dm.heightPixels, dm.widthPixels)
    }
    private lateinit var optimalSize: RecorderSize
    private lateinit var mediaRecorderManager: MediaRecorderManager

    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler

    private val mCameraOpenCloseLock = Semaphore(1)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)
        startBackgroundThread()
        filePath = intent.getStringExtra(ARG_FILEPATH)
        mediaRecorderManager = MediaRecorderManager()

        BaseActivityPermissionsDispatcher.checkPermissionWithCheck(this)
    }

    override fun checkPermission() {
        super.checkPermission()
        val holder = recorder_vv.holder
        openCamera(holder, initSize.width, initSize.height)
    }

    override fun onStop() {
        closeCamera()
        stopBackgroundThread()
        super.onStop()
    }

    override fun touched() {
        super.touched()
        initCameraDevice(mCameraDevice)
    }

    override fun untouched() {
        super.untouched()
        recordComplete()
    }

    override fun recording(): Boolean {
        duration++
        val sec = duration / 10.0
        recorder_progress.setProgress((sec / MAX_DURATION * 100).toInt())
        if (sec > MAX_DURATION) {
            recordComplete()
            return false
        }
        return true
    }

    private fun recordComplete() {
        if (isRecording) {
            isRecording = false
            mediaRecorderManager.stopRecord()
            closeCamera()
            startShowCompleteAni()
            play(filePath)
        }
    }

    override fun cancelButton() {
        super.cancelButton()
        File(filePath).run { delete() }
        val holder = recorder_vv.holder
        openCamera(holder, initSize.width, initSize.height)
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

    private fun startPreview() {
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