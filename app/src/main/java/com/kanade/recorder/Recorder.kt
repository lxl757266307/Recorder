package com.kanade.recorder

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import kotlinx.android.synthetic.main.activity_recorder.*
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.OnShowRationale
import permissions.dispatcher.PermissionRequest
import permissions.dispatcher.RuntimePermissions
import android.support.v7.app.AlertDialog
import android.util.Log
import android.view.ViewConfiguration
import android.widget.Toast
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder._interface.IRecorderContract
import com.kanade.recorder.widget.VideoProgressBtn
import permissions.dispatcher.OnNeverAskAgain
import permissions.dispatcher.OnPermissionDenied

@RuntimePermissions
class Recorder : AppCompatActivity(), View.OnClickListener, IRecorderContract.View,
        VideoProgressBtn.AniEndListener, MediaPlayer.OnPreparedListener {
    private val TAG = "Recorder"
    private lateinit var presenter: IRecorderContract.Presenter
    private lateinit var handler: Handler
    // 是否正在播放
    private var isPlaying = false

    private val focusAni by lazy { initFocusViewAni() }
    private val showCompleteAni by lazy { initShowCompleteViewAni() }
    private val hideCompleteAni by lazy { initHideCompleteViewAni() }
    private val scaleGestureListener by lazy { initScaleGestureListener() }
    private val runnable: Runnable by lazy { initRunnable() }

    companion object {
        private const val RESULT_FILEPATH = "result_filepath"
        private const val ARG_FILEPATH = "arg_filepath"
        @JvmStatic
        fun newIntent(ctx: Context, filePath: String): Intent =
                Intent(ctx, Recorder::class.java)
                        .putExtra(ARG_FILEPATH, filePath)

        @JvmStatic
        fun getResult(intent: Intent): RecorderResult =
                intent.getParcelableExtra(RESULT_FILEPATH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,  WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_recorder)
        val filePath = savedInstanceState?.getString(ARG_FILEPATH) ?: intent.getStringExtra(ARG_FILEPATH)
        presenter = RecorderPresenter()
        presenter.attach(this, filePath)

        RecorderPermissionsDispatcher.initViewWithCheck(this)
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    @NeedsPermission(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    fun initView() {
        handler = Handler()

        // 先让video_record_focus渲染一次，下面要用到其宽高
        focusAni.start()

        recorder_back.setOnClickListener(this)
        recorder_cancelbtn.setOnClickListener(this)
        recorder_certainbtn.setOnClickListener(this)

        recorder_progress.setListener(this)
        recorder_progress.setOnTouchListener(progressOnTouched())
        recorder_vv.setOnPreparedListener(this)
        recorder_vv.setOnTouchListener(vvOnTouched)

        recorder_vv.viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                recorder_vv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                presenter.init(recorder_vv.holder, recorder_vv.width, recorder_vv.height)
                presenter.startPreview()
            }
        })
    }

    @OnShowRationale(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    fun showRationaleForRecorder(request: PermissionRequest) {
        AlertDialog.Builder(this)
                .setMessage(R.string.permission_recorder_rationale)
                .setPositiveButton(R.string.button_allow, { _, _ -> request.proceed() })
                .setNegativeButton(R.string.button_deny, { _, _ -> request.cancel(); finish() })
                .show()
    }

    @OnPermissionDenied(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    fun showDeniedForRecorder() {
        Toast.makeText(this, R.string.permission_recorder_denied, Toast.LENGTH_SHORT).show()
        finish()
    }

    @OnNeverAskAgain(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    fun showNeverAskForRecorder() {
        Toast.makeText(this, R.string.permission_recorder_denied, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        RecorderPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.detach()
    }

    override fun updateProgress(p: Int) {
        recorder_progress.setProgress(p)
    }

    override fun recordComplete() {
        recorder_progress.recordComplete()
        showCompleteView()
    }

    private fun focus(x: Float, y: Float) {
//        Log.d(TAG, "focus x: $x, focus y: $y")
//        Log.d(TAG, "focus Width: ${recorder_focus_btn.width}, focus Height: ${recorder_focus_btn.height}")
        recorder_focus.x = x - recorder_focus.width / 2
        recorder_focus.y = y - recorder_focus.height / 2
        if (focusAni.isRunning) {
            focusAni.cancel()
        }
        focusAni.start()
        presenter.handleFocusMetering(x, y)
    }

    private fun showCompleteView() {
        if (showCompleteAni.isRunning) {
            showCompleteAni.cancel()
        }
        showCompleteAni.start()
    }

    private fun hideCompleteView() {
        if (hideCompleteAni.isRunning) {
            hideCompleteAni.cancel()
        }
        hideCompleteAni.start()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.recorder_back -> finish()
            R.id.recorder_cancelbtn -> { hideCompleteView(); presenter.reconnect() }
            R.id.recorder_certainbtn -> presenter.setResult()
        }
    }

    override fun setResult(result: RecorderResult) {
        val data = Intent()
        data.putExtra(RESULT_FILEPATH, result)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun playVideo(filePath: String) {
        if (isPlaying) return
        recorder_vv.setVideoPath(filePath)
        recorder_vv.start()
    }

    override fun stopVideo() {
        if (!isPlaying) return
        isPlaying = false
        recorder_vv.stopPlayback()
    }

    override fun onPrepared(mp: MediaPlayer) {
        isPlaying = true
        mp.isLooping = true
        mp.start()
    }

    override fun touched() {
        handler.removeCallbacks(runnable)
        handler.post(runnable)
        presenter.staretRecord()
    }

    override fun untouched() {
        recorder_progress.setProgress(0)
        handler.removeCallbacks(runnable)
        presenter.recordComplete()
    }

    private fun initRunnable() = Runnable {
        presenter.recording()
        handler.postDelayed(runnable, 100)
    }

    /**
     * videoview触摸事件，将会处理两种事件: 双指缩放，单击对焦
     *
     * 当正处于播放录像的时候将不会进行处理
     */
    private val vvOnTouched = View.OnTouchListener { _, event ->
        if (isPlaying) return@OnTouchListener true
        return@OnTouchListener scaleGestureListener.onTouched(event)
    }

    private fun initScaleGestureListener(): ScaleGestureImpl =
            ScaleGestureImpl(this, object : ScaleGestureImpl.GestureListener {
                override fun onSingleTap(event: MotionEvent) = focus(event.x, event.y)
                override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) {
                    Log.d(TAG, "缩放")
                }
            })

    private inner class progressOnTouched : View.OnTouchListener {
        private var downToucheY = 0f
        private var touchSlop = 0f
        init {
            val configuration = ViewConfiguration.get(this@Recorder)
            touchSlop = configuration.scaledTouchSlop.toFloat()
        }
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downToucheY = event.y
                    recorder_progress.startRecord()
                }
                MotionEvent.ACTION_MOVE -> {
                    val distance = downToucheY - event.y
                    if (distance > touchSlop) {
                        Log.d(TAG, "pregress btn 上滑")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    downToucheY = Float.MAX_VALUE
                    recorder_progress.recordComplete()
                }
            }
            return true
        }
    }

    private fun initFocusViewAni(): AnimatorSet {
        val focusScaleXAni = ObjectAnimator.ofFloat(recorder_focus, "scaleX", 1.5f, 1f)
        val focusScaleYAni = ObjectAnimator.ofFloat(recorder_focus, "scaleY", 1.5f, 1f)
        val focusAlphaAni = ObjectAnimator.ofFloat(recorder_focus, "alpha", 1f, 0f)
        val set = AnimatorSet()
        focusScaleXAni.duration = 500
        focusScaleYAni.duration = 500
        focusAlphaAni.duration = 750
        set.run {
            play(focusScaleXAni)
                    .with(focusScaleYAni)
                    .before(focusAlphaAni)
            addListener(object :AnimatorListenerAdapter(){
                override fun onAnimationStart(animation: Animator?) {
                    super.onAnimationStart(animation)
                    recorder_focus.alpha = 1f
                    recorder_focus.visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    super.onAnimationEnd(animation)
                    recorder_focus.visibility = GONE
                }
            })
        }
        return set
    }

    private fun initShowCompleteViewAni(): AnimatorSet {
        val cancelXAni = ObjectAnimator.ofFloat(recorder_cancelbtn, "scaleX", 0f, 1f)
        val cancelYAni = ObjectAnimator.ofFloat(recorder_cancelbtn, "scaleY", 0f, 1f)
        val certainXAni = ObjectAnimator.ofFloat(recorder_certainbtn, "scaleX", 0f, 1f)
        val certainYAni = ObjectAnimator.ofFloat(recorder_certainbtn, "scaleY", 0f, 1f)
        val processXAni = ObjectAnimator.ofFloat(recorder_progress, "scaleX", 1f, 0f)
        val processYAni = ObjectAnimator.ofFloat(recorder_progress, "scaleY", 1f, 0f)
        val set = AnimatorSet()
        set.run {
            duration = 350
            playTogether(cancelXAni, cancelYAni, certainXAni, certainYAni, processXAni, processYAni)
            addListener(object :AnimatorListenerAdapter(){
                override fun onAnimationStart(animation: Animator?) {
                    super.onAnimationStart(animation)
                    recorder_back.visibility = GONE
                    recorder_cancelbtn.visibility = VISIBLE
                    recorder_certainbtn.visibility = VISIBLE
                }

                override fun onAnimationEnd(animation: Animator?) {
                    super.onAnimationEnd(animation)
                    recorder_progress.visibility = GONE
                }
            })
        }
        return set
    }

    private fun initHideCompleteViewAni(): AnimatorSet {
        val cancelXAni = ObjectAnimator.ofFloat(recorder_cancelbtn, "scaleX", 1f, 0f)
        val cancelYAni = ObjectAnimator.ofFloat(recorder_cancelbtn, "scaleY", 1f, 0f)
        val certainXAni = ObjectAnimator.ofFloat(recorder_certainbtn, "scaleX", 1f, 0f)
        val certainYAni = ObjectAnimator.ofFloat(recorder_certainbtn, "scaleY", 1f, 0f)
        val processXAni = ObjectAnimator.ofFloat(recorder_progress, "scaleX", 0f, 1f)
        val processYAni = ObjectAnimator.ofFloat(recorder_progress, "scaleY", 0f, 1f)
        val set = AnimatorSet()
        set.run {
            duration = 350
            playTogether(cancelXAni, cancelYAni, certainXAni, certainYAni, processXAni, processYAni)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    super.onAnimationEnd(animation)
                    recorder_progress.visibility = VISIBLE
                    recorder_back.visibility = VISIBLE
                    recorder_cancelbtn.visibility = GONE
                    recorder_certainbtn.visibility = GONE
                }
            })
        }
        return set
    }

    override fun getContext(): Context = getContext()
}