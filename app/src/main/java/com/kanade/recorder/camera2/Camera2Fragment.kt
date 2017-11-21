package com.kanade.recorder.camera2

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
import android.support.v4.app.Fragment
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.kanade.recorder.PreviewFragment
import com.kanade.recorder.R
import com.kanade.recorder.Recorder
import com.kanade.recorder.RecorderResult
import com.kanade.recorder.Utils.*
import com.kanade.recorder.camera1.Camera1Fragment
import com.kanade.recorder.widget.AutoFitTextureView
import com.kanade.recorder.widget.RecorderButton
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class Camera2Fragment : Fragment(), RecorderButton.Listener, View.OnClickListener {
    companion object {
        private const val TAG = "Camera2"
        @JvmStatic
        fun newInstance(filepath: String): Camera2Fragment {
            val args = Bundle()
            args.putString(Recorder.ARG_FILEPATH, filepath)

            val fragment = Camera2Fragment()
            fragment.arguments = args
            return fragment
        }
    }

    // 录像时记录已录制时长(用于限制最大录制时间)
    private var duration = 0
    // 录像文件保存位置
    private lateinit var filepath: String

    private lateinit var textureView: AutoFitTextureView
    private lateinit var scaleTextView: TextView
    private lateinit var flashImageView: ImageView
    private lateinit var recorderBtn: RecorderButton
    private lateinit var backBtn: ImageView

    private lateinit var viewSize: RecorderSize
    private lateinit var optimalSize: RecorderSize
    private lateinit var characteristics: CameraCharacteristics
    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mCameraBuilder: CaptureRequest.Builder
    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler


    private var mCameraRequest: CaptureRequest? = null
    private var mCameraSession: CameraCaptureSession? = null

    private val recorderManager by lazy { MediaRecorderManager(true) }
    private val scaleGesture by lazy { ScaleGestureDetector(context, scaleGestureListener) }

    // 上一个缩放值
    private var lastScale = 1f
    private val mCameraOpenCloseLock = Semaphore(1)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_camera2, container, false)

    private fun initView(view: View) {
        textureView = view.findViewById(R.id.recorder_tv)
        scaleTextView = view.findViewById(R.id.recorder_scale)
        flashImageView = view.findViewById(R.id.recorder_flash)
        recorderBtn = view.findViewById(R.id.recorder_progress)
        backBtn = view.findViewById(R.id.recorder_back)

        flashImageView.tag = false
        flashImageView.setOnClickListener(this)
        backBtn.setOnClickListener(this)
        textureView.setOnTouchListener { _, event -> scaleGesture.onTouchEvent(event) }
        recorderBtn.setListener(this)
        recorderBtn.setOnTouchListener(btnTouchListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
        val dm = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(dm)
        viewSize = RecorderSize(dm.heightPixels, dm.widthPixels)
        filepath = arguments.getString(Recorder.ARG_FILEPATH)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        characteristics = manager.getCameraCharacteristics(cameraId) as CameraCharacteristics
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onClick(v: View) {
        if (v.id == R.id.recorder_flash) {
            val flashMode = flashImageView.tag as Boolean
            if (flashMode) {
                mCameraBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                updatePreview()
                flashImageView.setImageResource(R.drawable.flash_off)
            } else {
                mCameraBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                updatePreview()
                flashImageView.setImageResource(R.drawable.flash_on)
            }
            flashImageView.tag = !flashMode
        } else if (v.id == R.id.recorder_back) {
            activity.finish()
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

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            closePreviewSession()
            mCameraDevice.close()
            recorderManager.releaseMediaRecorder()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

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
            activity.supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.recorder_fl, Camera1Fragment.newInstance(filepath))
                    .addToBackStack(null)
                    .commit()
        }
    }

    private fun startPreview() {
        closePreviewSession()
        try {
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(optimalSize.width, optimalSize.height)

            val previewSurface = Surface(texture)
            mCameraBuilder.addTarget(previewSurface)
            mCameraBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90)
            mCameraBuilder.set(CaptureRequest.JPEG_QUALITY, 90)

            mCameraDevice.createCaptureSession(listOf(previewSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            mCameraSession = session
                            updatePreview()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(context, getString(R.string.start_preview_failed), Toast.LENGTH_SHORT).show()
                        }
                    }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        try {
            mCameraBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            mCameraBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            mCameraRequest = mCameraBuilder.build()
            mCameraSession?.setRepeatingRequest(mCameraRequest, null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closePreviewSession() {
        mCameraSession?.close()
        mCameraSession = null
    }

    override fun onRecorderBtnTouched() {
        recorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filepath)
    }

    override fun onRecorderBtnPressed() {
        if (!recorderManager.isPrepare()) {
            return
        }
        closePreviewSession()
        try {
            val preBuilder = mCameraBuilder
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            // 保持闪光灯的状态
            mCameraBuilder.set(CaptureRequest.FLASH_MODE, preBuilder.get(CaptureRequest.FLASH_MODE))

            val texture = textureView.surfaceTexture
            val previewSurface = Surface(texture)

            // Set up Surface for the camera preview
            val surfaces = ArrayList<Surface>()
            surfaces.add(previewSurface)
            mCameraBuilder.addTarget(previewSurface)

            // Set up Surface for the MediaRecorder
            recorderManager.getRecorder()?.surface?.let {
                duration = 0
                surfaces.add(it)
                mCameraBuilder.addTarget(it)
                mCameraBuilder.set(CaptureRequest.SCALER_CROP_REGION, preBuilder.get(CaptureRequest.SCALER_CROP_REGION))
            }

            mCameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    mCameraSession = cameraCaptureSession
                    updatePreview()

                    activity.runOnUiThread {
                        recorderManager.startRecord()
                    }
                    recorderBtn.removeCallbacks(runnable)
                    recorderBtn.post(runnable)
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(context, getString(R.string.open_camera_failed), Toast.LENGTH_SHORT).show()
                }
            }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onRecorderBtnUp() {
        // reset the progress when the finger release
        recorderBtn.setProgress(0)
        // remove the runnable avoid still increasing duration
        recorderBtn.removeCallbacks(runnable)
        recordComplete()
    }

    private val runnable: Runnable by lazy { initRunnable() }
    private fun recordComplete() {
        if (!recorderManager.isRecording()) {
            return
        }

        closePreviewSession()
        recorderManager.stopRecord()
        val sec = duration / 10.0
        if (sec < 1) {
            Toast.makeText(context, R.string.too_short, Toast.LENGTH_SHORT).show()
            startPreview()
        } else {
            recorderBtn.revert()
            recorderBtn.postDelayed({
                previewVideo()
            }, 300)
        }
    }

    private fun previewVideo() {
        val result = RecorderResult(filepath, (duration / 10.0).toInt(), optimalSize.width, optimalSize.height)
        activity.supportFragmentManager
                .beginTransaction()
                .replace(R.id.recorder_fl, PreviewFragment.newInstance(result))
                .commit()
    }

    private fun initRunnable() = Runnable {
        if (!recorderManager.isRecording()) {
            return@Runnable
        }
        duration++
        val sec = duration / 10.0
        val progress = (sec / Recorder.DURATION_LIMIT * 100).toInt()
        recorderBtn.setProgress(progress)
        if (sec > Recorder.DURATION_LIMIT) {
            recordComplete()
            return@Runnable
        }
        recorderBtn.postDelayed(runnable, 100)
    }

    private fun openCamera(width: Int, height: Int) {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(MediaRecorder::class.java)
                    .map { RecorderSize(it.height, it.width) }
            val screenProp = height / width.toFloat()
            optimalSize = getBestSize(map, 1000, screenProp)

            textureView.setAspectRatio(optimalSize.width, optimalSize.height)
            configureTransform(optimalSize.width, optimalSize.height)
            manager.openCamera(cameraId, mStateCallback, null)
        } catch (e: CameraAccessException) {
            Toast.makeText(context, R.string.start_preview_failed, Toast.LENGTH_SHORT).show()
            // open the fragment of deprecated camera api
            activity.supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.recorder_fl, Camera1Fragment.newInstance(filepath))
                    .addToBackStack(null)
                    .commit()
        } catch (e: NullPointerException) {
            showErrorDialog()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = activity.windowManager.defaultDisplay.rotation
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
        AlertDialog.Builder(context)
                .setMessage(R.string.camera_error)
                .setPositiveButton(R.string.button_positive, { d, _ -> d.dismiss(); activity.finish() })
                .show()
    }

    private val btnTouchListener = object : View.OnTouchListener {
        private var lastTouchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.rawY
                    recorderBtn.scale()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 滑动低于按钮位置时不缩放
                    if (event.rawY >= recorderBtn.y) {
                        return true
                    }
                    if ((Math.abs(lastTouchY - event.rawY)) < 10) {
                        return true
                    }
                    zoom(lastTouchY >= event.rawY)
                    lastTouchY = event.rawY
                }
                MotionEvent.ACTION_UP -> recorderBtn.revert()
            }
            return true
        }
    }

    private fun zoom(zoomIn: Boolean) {
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).toInt()
        if (zoomIn) {
            lastScale += 0.05f
        } else {
            lastScale -= 0.15f
        }

        if (lastScale > maxZoom) {
            lastScale = maxZoom.toFloat()
        } else if (lastScale < 1) {
            lastScale = 1f
        }
        zoom(lastScale)
    }

    private fun zoom(scale: Float) {
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorRect?.let { rect ->
            var left = rect.width() / 2
            var right = left
            var top = rect.height() / 2
            var bottom = top
            val hwidth = (rect.width() / (2.0 * scale)).toInt()
            val hheight = (rect.height() / (2.0 * scale)).toInt()

            left -= hwidth
            right += hwidth
            top -= hheight
            bottom += hheight

            val newRect = Rect(left, top, right, bottom)
            mCameraBuilder.set(CaptureRequest.SCALER_CROP_REGION, newRect)
            updatePreview()
            lastScale = scale
            setScaleViewText(lastScale)
        }
    }

    private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val different = detector.currentSpan - detector.previousSpan
            if (Math.abs(different) >= 10) {
                zoom(different > 0)
                return true
            }
            return false
        }
    }

    private fun setScaleViewText(scale: Float) {
        if (scale == 1f) {
            scaleTextView.visibility = View.GONE
        } else {
            scaleTextView.visibility = View.VISIBLE
            scaleTextView.text = String.format("x %.2f", scale)
        }
    }
}