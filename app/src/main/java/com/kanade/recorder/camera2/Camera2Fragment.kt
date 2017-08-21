package com.kanade.recorder.camera2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import com.kanade.recorder.AnimatorSet
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder.PreviewFragment
import com.kanade.recorder.R
import com.kanade.recorder.RecorderActivity
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.RecorderSize
import com.kanade.recorder.Utils.getBestSize
import com.kanade.recorder.Utils.initProfile
import com.kanade.recorder.camera1.Camera1Fragment
import com.kanade.recorder.widget.AutoFitTextureView
import com.kanade.recorder.widget.VideoProgressBtn
import java.io.IOException
import java.util.ArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class Camera2Fragment : Fragment(), VideoProgressBtn.Listener {
    companion object {
        @JvmStatic
        fun newInstance(filepath: String): Camera2Fragment {
            val args = Bundle()
            args.putString(RecorderActivity.ARG_FILEPATH, filepath)

            val fragment = Camera2Fragment()
            fragment.arguments = args
            return fragment
        }
    }

    // 录像时记录已录制时长(用于限制最大录制时间)
    private var duration = 0
    // 录像文件保存位置
    private lateinit var filepath: String
    private lateinit var recorderManager: MediaRecorderManager

    private lateinit var textureView: AutoFitTextureView
    private lateinit var progressBtn: VideoProgressBtn
    private lateinit var focusBtn: ImageView
    private lateinit var backBtn: ImageView

    private lateinit var optimalSize: RecorderSize
    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mCameraBuilder: CaptureRequest.Builder
    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler

    private val scaleGestureListener by lazy { initScaleGestureListener() }
    private var mCameraSession: CameraCaptureSession? = null

    // 上一个缩放值
    private var lastZoom = 1f
    private val mCameraOpenCloseLock = Semaphore(1)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_camera2, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        textureView = view.findViewById(R.id.recorder_tv) as AutoFitTextureView
        progressBtn = view.findViewById(R.id.recorder_progress) as VideoProgressBtn
        focusBtn = view.findViewById(R.id.recorder_focus) as ImageView
        backBtn = view.findViewById(R.id.recorder_back) as ImageView

        backBtn.setOnClickListener{ _ -> activity.finish()}
        textureView.setOnTouchListener{ _, event -> scaleGestureListener.onTouched(event)}
        progressBtn.setListener(this)
        progressBtn.setOnTouchListener(progressBtnTouched())
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        filepath = arguments.getString(RecorderActivity.ARG_FILEPATH)
        recorderManager = MediaRecorderManager()
        focusAni.start()
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

    override fun onPressed() {
        closePreviewSession()
        try {
            val preBuilder = mCameraBuilder
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            recorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filepath)

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
                        isRecording = true
                    }
                    progressBtn.removeCallbacks(runnable)
                    progressBtn.post(runnable)
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(context, getString(R.string.start_record_failed), Toast.LENGTH_SHORT).show()
                }
            }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onRelease() {
        // reset the progress when the finger release
        progressBtn.setProgress(0)
        // remove the runnable avoid still increasing duration
        progressBtn.removeCallbacks(runnable)
        recordComplete()
    }

    private var isRecording = false
    private val runnable: Runnable by lazy { initRunnable() }
    private fun recordComplete() {
        if (!isRecording) {
            return
        }

        closePreviewSession()
        recorderManager.stopRecord()
        isRecording = false
        val sec = duration / 10.0
        if (sec < 1) {
            Toast.makeText(context, R.string.record_too_short, Toast.LENGTH_LONG).show()
            startPreview()
        } else {
            progressBtn.recordComplete()
            progressBtn.postDelayed({
                previewVideo()
            }, 300)
        }
    }

    private fun previewVideo() {
        activity.supportFragmentManager
                .beginTransaction()
                .replace(R.id.recorder_fl, PreviewFragment.newInstance(filepath, duration))
                .addToBackStack(null)
                .commit()
    }

    private fun initRunnable() = Runnable {
        if (!isRecording) {
            return@Runnable
        }
        duration++
        val sec = duration / 10.0
        val progress = (sec / RecorderActivity.DURATION_LIMIT * 100).toInt()
        progressBtn.setProgress(progress)
        if (sec > RecorderActivity.DURATION_LIMIT) {
            recordComplete()
            return@Runnable
        }
        progressBtn.postDelayed(runnable, 100)
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

            textureView.setAspectRatio(optimalSize.height, optimalSize.width)
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

    private inner class progressBtnTouched : View.OnTouchListener {
        private var lastTouchY = 0f
        private var touchSlop = 0f

        init {
            val configuration = ViewConfiguration.get(context)
            touchSlop = configuration.scaledTouchSlop.toFloat()
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = textureView.y
                    progressBtn.startRecord()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 向下手势滑动，和向上手势滑动距离过短也不触发缩放事件
                    if (event.y >= textureView.y) {
                        return true
                    }
                    zoom(Math.abs(lastTouchY) <= Math.abs(event.y))
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP -> progressBtn.recordComplete()
            }
            return true
        }
    }

    private fun initScaleGestureListener() = ScaleGestureImpl(context, object : ScaleGestureImpl.GestureListener {
        override fun onSingleTap(event: MotionEvent) {
            // 录像按钮以下位置不允许对焦
            if (event.y < progressBtn.y) {
                val x = event.x
                val y = event.y
                focusBtn.x = x - focusBtn.width / 2
                focusBtn.y = y - focusBtn.height / 2
                startFocusAni()
            }
        }

        override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
            zoom(scaleFactor > 1f)
        }
    })

    private fun zoom(zoomIn: Boolean) {
        if (zoomIn) {
            lastZoom += 0.05f
        } else {
            if (lastZoom > 1) {
                lastZoom -= 0.15f
            }
        }
        zoom(lastZoom)
    }

    private fun zoom(zoom: Float) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
            lastZoom = z
        }
    }

    private val focusAni by lazy { initFocusViewAni() }
    private fun startFocusAni() {
        if (focusAni.isRunning) {
            focusAni.cancel()
        }
        focusAni.start()
    }


    private fun initFocusViewAni() = AnimatorSet {
        setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator?) {
                super.onAnimationStart(animation)
                focusBtn.alpha = 1f
                focusBtn.visibility = View.VISIBLE
            }

            override fun onAnimationEnd(animation: Animator?) {
                super.onAnimationEnd(animation)
                focusBtn.visibility = View.GONE
            }
        })
        invoke {
            play(ObjectAnimator.ofFloat(focusBtn, "scaleX", 1.5f, 1f).apply { duration = 500 })
                    .with(ObjectAnimator.ofFloat(focusBtn, "scaleY", 1.5f, 1f).apply { duration = 500 })
                    .before(ObjectAnimator.ofFloat(focusBtn, "alpha", 1f, 0f).apply { duration = 750 })
        }
    }
}