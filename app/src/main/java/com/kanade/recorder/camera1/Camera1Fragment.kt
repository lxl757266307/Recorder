package com.kanade.recorder.camera1

import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
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
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.Utils.initProfile
import com.kanade.recorder.widget.RecorderButton

class Camera1Fragment : Fragment(), View.OnClickListener, MediaRecorderManager.MediaStateListener, SurfaceHolder.Callback, RecorderButton.Listener {
    companion object {
        private const val TAG = "Camera1Fragment"
        @JvmStatic
        fun newInstance(filepath: String): Camera1Fragment {
            val args = Bundle()
            args.putString(Recorder.ARG_FILEPATH, filepath)

            val fragment = Camera1Fragment()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var surfaceview: SurfaceView
    private lateinit var scaleTextView: TextView
    private lateinit var flashImageView: ImageView
    private lateinit var recorderBtn: RecorderButton
    private lateinit var backBtn: ImageView

    // 录像时长(用于限制录像最大时长)
    private var duration: Int = 0
    // 录像文件保存地址
    private lateinit var filepath: String
    private val cameraManager by lazy {
        val holder = surfaceview.holder
        val dm = DisplayMetrics()

        activity.windowManager.defaultDisplay.getMetrics(dm)
        CameraManager(holder, dm.widthPixels, dm.heightPixels).apply {
            holder.addCallback(this@Camera1Fragment)
        }
    }
    private val recorderManager by lazy {
        MediaRecorderManager(false).apply {
            setListener(this@Camera1Fragment)
        }
    }

    private val scaleGesture by lazy { ScaleGestureDetector(context, scaleGestureListener) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        filepath = arguments.getString(Recorder.ARG_FILEPATH)
        val view = inflater.inflate(R.layout.fragment_camera1, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        surfaceview = view.findViewById(R.id.recorder_sv)
        scaleTextView = view.findViewById(R.id.recorder_scale)
        flashImageView = view.findViewById(R.id.recorder_flash)
        recorderBtn = view.findViewById(R.id.recorder_progress)
        backBtn = view.findViewById(R.id.recorder_back)

        recorderBtn.setListener(this)
        recorderBtn.setOnTouchListener(btnTouchListener)
        flashImageView.tag = false
        flashImageView.setOnClickListener(this)
        backBtn.setOnClickListener(this)
        surfaceview.setOnTouchListener { _, event ->
            scaleGesture.onTouchEvent(event)
        }
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraManager.startPreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recorderManager.releaseMediaRecorder()
        cameraManager.releaseCamera()
    }

    override fun onClick(v: View) {
        if (v.id == R.id.recorder_flash) {
            val flashMode = flashImageView.tag as Boolean
            if (flashMode) {
                cameraManager.closeFlash()
                flashImageView.setImageResource(R.drawable.flash_off)
            } else {
                cameraManager.openFlash()
                flashImageView.setImageResource(R.drawable.flash_on)
            }
            flashImageView.tag = !flashMode
        } else if (v.id == R.id.recorder_back) {
            activity.finish()
        }
    }

    private val runnable: Runnable by lazy { initRunnable() }
    private val profile: CamcorderProfile by lazy {
        val size = cameraManager.getVideoSize()
        initProfile(size.first, size.second)
    }

    override fun onRecorderBtnTouched() {
        recorderManager.prepareRecord(profile, filepath)
    }

    override fun onRecorderBtnPressed() {
        duration = 0
        recorderBtn.removeCallbacks(runnable)
        if (recorderManager.isPrepare()) {
            recorderManager.startRecord()
            recorderBtn.post(runnable)
        }
    }

    override fun onRecorderBtnUp() {
        // 当手指放开即马上重置按钮的进度条，并停止runnable(避免录音时长仍在增加)
        recorderBtn.setProgress(0)
        recorderBtn.removeCallbacks(runnable)
        recordComplete()
    }

    private fun recordComplete() {
        if (!recorderManager.isRecording()) {
            return
        }

        recorderManager.stopRecord()
        val sec = duration / 10.0
        // 录制时间过短时，不需要释放camera(因为还需要继续录制)
        if (sec < 1) {
            Toast.makeText(context, R.string.too_short, Toast.LENGTH_SHORT).show()
        } else {
            cameraManager.releaseCamera()
            previewVideo()
        }
    }

    private fun previewVideo() {
        val size = cameraManager.getVideoSize()
        val result = RecorderResult(filepath, (duration / 10.0).toInt(), size.first, size.second)
        activity.supportFragmentManager
                .beginTransaction()
                .replace(R.id.recorder_fl, PreviewFragment.newInstance(result))
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        cameraManager.releaseCamera()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cameraManager.updateHolder(holder)
    }

    private val btnTouchListener = object : View.OnTouchListener {
        private var lastTouchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.rawY
                    recorderBtn.scale()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 滑动低于按钮位置时不缩放
                    if (event.y >= recorderBtn.y) {
                        return true
                    }
                    if ((Math.abs(lastTouchY - event.rawY)) < 10) {
                        return true
                    }
                    val ratio = cameraManager.zoom(lastTouchY >= event.rawY)
                    setScaleViewText(ratio)
                    lastTouchY = event.rawY
                }
                MotionEvent.ACTION_UP -> recorderBtn.revert()
            }
            return true
        }
    }

    private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val different = detector.currentSpan - detector.previousSpan
            if (Math.abs(different) >= 10) {
                val ratio = cameraManager.zoom(different > 0)
                setScaleViewText(ratio)
                return true
            }
            return false
        }
    }

    private fun setScaleViewText(ratio: Int) {
        if (ratio == -1 || ratio == 100) {
            scaleTextView.visibility = View.GONE
        } else {
            val realRatio = ratio / 100f
            scaleTextView.visibility = View.VISIBLE
            scaleTextView.text = String.format("x %.2f", realRatio)
        }
    }
}