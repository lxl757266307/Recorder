package com.kanade.recorder.camera1

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.Fragment
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageView
import android.widget.Toast
import com.kanade.recorder.AnimatorSet
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder.PreviewFragment
import com.kanade.recorder.R
import com.kanade.recorder.RecorderActivity
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.initProfile
import com.kanade.recorder.widget.VideoProgressBtn

class Camera1Fragment : Fragment(), View.OnClickListener, MediaRecorderManager.MediaStateListener, SurfaceHolder.Callback, VideoProgressBtn.Listener {
    companion object {
        @JvmStatic
        fun newInstance(filepath: String): Camera1Fragment {
            val args = Bundle()
            args.putString(RecorderActivity.ARG_FILEPATH, filepath)

            val fragment = Camera1Fragment()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var surfaceview: SurfaceView
    private lateinit var progressBtn: VideoProgressBtn
    private lateinit var focusBtn: ImageView
    private lateinit var backBtn: ImageView

    private var isRecording = false
    // 录像时长(用于限制录像最大时长)
    private var duration: Int = 0
    // 录像文件保存地址
    private lateinit var filepath: String
    private lateinit var cameraManager: CameraManager
    private lateinit var recorderManager: MediaRecorderManager

    private val gestureListener by lazy { initScaleGestureListener() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        filepath = arguments.getString(RecorderActivity.ARG_FILEPATH)
        val view = inflater.inflate(R.layout.fragment_camera1, container, false)
        initView(view)
        initCameraManager()
        initRecorderManager()
        return view
    }

    private fun initView(view: View) {
        surfaceview = view.findViewById(R.id.recorder_sv) as SurfaceView
        progressBtn = view.findViewById(R.id.recorder_progress) as VideoProgressBtn
        focusBtn = view.findViewById(R.id.recorder_focus) as ImageView
        backBtn = view.findViewById(R.id.recorder_back) as ImageView

        progressBtn.setListener(this)
        progressBtn.setOnTouchListener(progressBtnTouched())

        backBtn.setOnClickListener(this)
        // videoview触摸事件，将会处理两种事件: 双指缩放，单击对焦
        surfaceview.setOnTouchListener { _, event -> gestureListener.onTouched(event) }
    }

    private fun initCameraManager() {
        val holder = surfaceview.holder
        val dm = DisplayMetrics()

        activity.windowManager.defaultDisplay.getMetrics(dm)
        cameraManager = CameraManager()
        cameraManager.init(holder, dm.widthPixels, dm.heightPixels)
        holder.addCallback(this)
    }

    private fun initRecorderManager() {
        recorderManager = MediaRecorderManager()
        recorderManager.setListener(this)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraManager.startPreview()
        focusAni.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recorderManager.releaseMediaRecorder()
        cameraManager.releaseCamera()
    }

    override fun onClick(v: View) {
        if (v.id == R.id.recorder_back) {
            activity.finish()
        }
    }

    private val runnable: Runnable by lazy { initRunnable() }
    private val profile: CamcorderProfile by lazy {
        val size = cameraManager.getVideoSize()
        initProfile(size.first, size.second)
    }

    override fun onPressed() {
        duration = 0
        if (recorderManager.prepareRecord(profile, filepath)) {
            recorderManager.startRecord()
            isRecording = true
        }
        progressBtn.removeCallbacks(runnable)
        progressBtn.post(runnable)
    }

    override fun onRelease() {
        // 当手指放开即马上重置按钮的进度条，并停止runnable(避免录音时长仍在增加)
        progressBtn.setProgress(0)
        progressBtn.removeCallbacks(runnable)
        recordComplete()
    }

    private fun recordComplete() {
        if (!isRecording) {
            return
        }

        recorderManager.stopRecord()
        isRecording = false
        val sec = duration / 10.0
        // 录制时间过短时，不需要释放camera(因为还需要继续录制)
        if (sec < 1) {
            Toast.makeText(context, R.string.record_too_short, Toast.LENGTH_LONG).show()
        } else {
            cameraManager.releaseCamera()
            previewVideo()
        }
    }

    private fun previewVideo() {
        activity.supportFragmentManager
                .beginTransaction()
                .replace(R.id.recorder_fl, PreviewFragment.newInstance(filepath, duration))
                .addToBackStack(null)
                .commit()
    }

    private var camera: Camera? = null
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

    /**
     * 递增duration，并更新按钮的进度条
     *
     * 当录制时间超过最大时长时，强制停止
     */
    private fun initRunnable() = Runnable {
        if (!isRecording) {
            return@Runnable
        }
        duration++
        val sec = duration / 10.0
        val progress = (sec / RecorderActivity.MAX_DURATION * 100).toInt()
        progressBtn.setProgress(progress)
        if (sec > RecorderActivity.MAX_DURATION) {
            recordComplete()
            return@Runnable
        }
        progressBtn.postDelayed(runnable, 100)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        cameraManager.releaseCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cameraManager.init(holder)
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
                    lastTouchY = surfaceview.y
                    progressBtn.startRecord()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 向下手势滑动，和向上手势滑动距离过短也不触发缩放事件
                    if (event.y >= surfaceview.y) {
                        return true
                    }
                    val isZoom = Math.abs(lastTouchY) <= Math.abs(event.y)
                    cameraManager.zoom(isZoom)
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
                cameraManager.FocusMetering(x, y)
            }
        }

        override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
            val isZoom = scaleFactor > 1f
            cameraManager.zoom(isZoom)
        }
    })

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