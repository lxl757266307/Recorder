package com.kanade.recorder.camera2

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.Fragment
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder.PreviewFragment
import com.kanade.recorder.R
import com.kanade.recorder.Recorder
import com.kanade.recorder.Utils.*
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
    private lateinit var recorderManager: MediaRecorderManager

    private lateinit var textureView: AutoFitTextureView
    private lateinit var progressBtn: VideoProgressBtn
    private lateinit var focusBtn: ImageView
    private lateinit var backBtn: ImageView

    private val camera2PreviewMatrix by lazy { Matrix() }
    private val preview2CameraMatrix by lazy { Matrix() }
    private lateinit var viewSize: RecorderSize
    private lateinit var optimalSize: RecorderSize
    private lateinit var characteristics: CameraCharacteristics
    private lateinit var mCameraDevice: CameraDevice
    private lateinit var mCameraBuilder: CaptureRequest.Builder
    private lateinit var mBackgroundThread: HandlerThread
    private lateinit var mBackgroundHandler: Handler

    private val scaleGestureListener by lazy { initScaleGestureListener() }
    private var mCameraRequest: CaptureRequest? = null
    private var mCameraSession: CameraCaptureSession? = null

    // 上一个缩放值
    private var lastScale = 1f
    private val mCameraOpenCloseLock = Semaphore(1)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera2, container, false)

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
        val dm = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(dm)
        viewSize = RecorderSize(dm.heightPixels, dm.widthPixels)
        filepath = arguments.getString(Recorder.ARG_FILEPATH)
        recorderManager = MediaRecorderManager()

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
        focusBtn.visibility = View.GONE
//        focusAni.start()
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

    override fun onTouched() {
        recorderManager.prepareRecord(initProfile(optimalSize.width, optimalSize.height), filepath)
    }

    override fun onPressed() {
        if (!recorderManager.isPrepare()) {
            return
        }
        closePreviewSession()
        try {
            val preBuilder = mCameraBuilder
            mCameraBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)

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

    private val runnable: Runnable by lazy { initRunnable() }
    private fun recordComplete() {
        if (!recorderManager.isRecording()) {
            return
        }

        closePreviewSession()
        recorderManager.stopRecord()
        val sec = duration / 10.0
        if (sec < 1) {
            Toast.makeText(context, R.string.record_too_short, Toast.LENGTH_SHORT).show()
            startPreview()
        } else {
            progressBtn.revert()
            progressBtn.postDelayed({
                previewVideo()
            }, 300)
        }
    }

    private fun previewVideo() {
        activity.supportFragmentManager
                .beginTransaction()
                .add(R.id.recorder_fl, PreviewFragment.newInstance(filepath, duration))
                .detach(this@Camera2Fragment)
                .addToBackStack(null)
                .commit()
    }

    private fun initRunnable() = Runnable {
        if (!recorderManager.isRecording()) {
            return@Runnable
        }
        duration++
        val sec = duration / 10.0
        val progress = (sec / Recorder.DURATION_LIMIT * 100).toInt()
        progressBtn.setProgress(progress)
        if (sec > Recorder.DURATION_LIMIT) {
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
                    progressBtn.scale()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 向下手势滑动，和向上手势滑动距离过短也不触发缩放事件
                    if (event.y >= textureView.y) {
                        return true
                    }
                    zoom(Math.abs(lastTouchY) <= Math.abs(event.y))
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP -> progressBtn.revert()
            }
            return true
        }
    }

    private fun initScaleGestureListener() = ScaleGestureImpl(context, object : ScaleGestureImpl.GestureListener {
        override fun onSingleTap(event: MotionEvent) {
            // 录像按钮以下位置不允许对焦
//            if (event.y < progressBtn.y) {
//                val x = event.x
//                val y = event.y
//                focusBtn.x = x - focusBtn.width / 2
//                focusBtn.y = y - focusBtn.height / 2
//                focus(x, y)
//                startFocusAni()
//            }
        }

        override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
            zoom(scaleFactor > 1f)
        }
    })

    private fun cancelAutoFocus() {
        mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        mCameraRequest = mCameraBuilder.build()
        mCameraSession?.capture(mCameraRequest, null, mBackgroundHandler)
        mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        mCameraSession?.setRepeatingRequest(mCameraRequest, null, mBackgroundHandler)
    }

    private fun focus(x: Float, y: Float) {
        cancelAutoFocus()
        val coords = floatArrayOf(x, y)
        calculateCameraToPreviewMatrix()
        camera2PreviewMatrix.invert(preview2CameraMatrix)
        preview2CameraMatrix.mapPoints(coords)
        val focus_x = coords[0]
        val focus_y = coords[1]
        val focusSize = 50
        val rect = Rect()
        rect.left = focus_x.toInt() - focusSize
        rect.right = focus_x.toInt() + focusSize
        rect.top = focus_y.toInt() - focusSize
        rect.bottom = focus_y.toInt() + focusSize
        if (rect.left < -1000) {
            rect.left = -1000
            rect.right = rect.left + 2 * focusSize
        } else if (rect.right > 1000) {
            rect.right = 1000
            rect.left = rect.right - 2 * focusSize
        }
        if (rect.top < -1000) {
            rect.top = -1000
            rect.bottom = rect.top + 2 * focusSize
        } else if (rect.bottom > 1000) {
            rect.bottom = 1000
            rect.top = rect.bottom - 2 * focusSize
        }

        val sensorRect = getViewableRect()
        val focusRect = arrayOf(convertAreaToMeteringRectangle(sensorRect, rect))
        if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
            mCameraBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, focusRect)
        }

        if (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
            mCameraBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, focusRect)
        }

        mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        mCameraRequest = mCameraBuilder.build()
        try {
            mCameraSession?.setRepeatingRequest(mCameraRequest, null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        mCameraSession?.capture(mCameraRequest, mAfCaptureCallback, mBackgroundHandler)
        mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
    }

    private fun calculateCameraToPreviewMatrix() {
        camera2PreviewMatrix.reset()
        camera2PreviewMatrix.setScale(1f, 1f)
        val result = (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) - 90 + 360) % 360
        camera2PreviewMatrix.postRotate(result.toFloat())
        // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
        // UI coordinates range from (0, 0) to (width, height).
        camera2PreviewMatrix.postScale(viewSize.width / 2000f, viewSize.height / 2000f)
        camera2PreviewMatrix.postTranslate(viewSize.width / 2f, viewSize.height / 2f)
    }

    private val mAfCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(result: CaptureResult) = Unit
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) = Unit
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val state = result.get(CaptureResult.CONTROL_AF_STATE)
            if (state == null || state != CameraMetadata.CONTROL_AF_MODE_AUTO) {
                mCameraBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                try {
                    mCameraBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    mCameraBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    mCameraRequest = mCameraBuilder.build()
                    mCameraSession?.setRepeatingRequest(mCameraRequest, null, mBackgroundHandler)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getViewableRect(): Rect {
        val cropRect = mCameraBuilder.get(CaptureRequest.SCALER_CROP_REGION)
        if (cropRect != null) {
            return cropRect
        }

        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorRect.right -= sensorRect.left
        sensorRect.left = 0
        sensorRect.bottom -= sensorRect.top
        sensorRect.top = 0
        return sensorRect
    }

    private fun convertAreaToMeteringRectangle(sensorRect: Rect, rect: Rect): MeteringRectangle {
        val camera2Rect = convertRectToCamera2(sensorRect, rect)
        return MeteringRectangle(camera2Rect, 1000)
    }

    private fun convertRectToCamera2(cropRect: Rect, rect: Rect): Rect {
        // for camera1 is always [-1000, -1000] to [1000, 1000] for the viewable region
        // but for camera2, we must convert to be relative to the crop region
        val left_f = (rect.left + 1000) / 2000.0
        val top_f = (rect.top + 1000) / 2000.0
        val right_f = (rect.right + 1000) / 2000.0
        val bottom_f = (rect.bottom + 1000) / 2000.0
        var left = (cropRect.left + left_f * (cropRect.width() - 1)).toInt()
        var right = (cropRect.left + right_f * (cropRect.width() - 1)).toInt()
        var top = (cropRect.top + top_f * (cropRect.height() - 1)).toInt()
        var bottom = (cropRect.top + bottom_f * (cropRect.height() - 1)).toInt()
        left = Math.max(left, cropRect.left)
        right = Math.max(right, cropRect.left)
        top = Math.max(top, cropRect.top)
        bottom = Math.max(bottom, cropRect.top)
        left = Math.min(left, cropRect.right)
        right = Math.min(right, cropRect.right)
        top = Math.min(top, cropRect.bottom)
        bottom = Math.min(bottom, cropRect.bottom)

        return Rect(left, top, right, bottom)
    }

    private fun zoom(zoomIn: Boolean) {
        if (zoomIn) {
            lastScale += 0.05f
        } else {
            if (lastScale > 1) {
                lastScale -= 0.15f
            }
        }
        zoom(lastScale)
    }

    private fun zoom(zoom: Float) {
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).toInt()
        val z = Math.min(maxZoom.toFloat(), zoom)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorRect?.let { rect ->
            var left = rect.width() / 2
            var right = left
            var top = rect.height() / 2
            var bottom = top
            val hwidth = (rect.width() / (2.0 * z)).toInt()
            val hheight = (rect.height() / (2.0 * z)).toInt()

            left -= hwidth
            right += hwidth
            top -= hheight
            bottom += hheight

            val newRect = Rect(left, top, right, bottom)
            mCameraBuilder.set(CaptureRequest.SCALER_CROP_REGION, newRect)
            updatePreview()
            lastScale = z
        }
    }

    private val focusAni by lazy { initFocusViewAni() }
    private fun startFocusAni() {
        if (focusAni.isRunning) {
            focusAni.cancel()
        }
        focusAni.start()
    }


    private fun initFocusViewAni(): AnimatorSet {
        return AnimatorSet().apply {
            addListener(object : AnimatorListenerAdapter() {
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
            play(ObjectAnimator.ofFloat(focusBtn, "scaleX", 1.5f, 1f).apply { duration = 500 })
                    .with(ObjectAnimator.ofFloat(focusBtn, "scaleY", 1.5f, 1f).apply { duration = 500 })
                    .before(ObjectAnimator.ofFloat(focusBtn, "alpha", 1f, 0f).apply { duration = 750 })
        }
    }
}