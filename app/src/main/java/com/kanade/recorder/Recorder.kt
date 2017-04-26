package com.kanade.recorder

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
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_recorder.*

class Recorder : AppCompatActivity(), View.OnClickListener, IRecorderContract.View, VideoProgressBtn.AniEndListener, MediaPlayer.OnPreparedListener {
    private val TAG = "Camera"

    private lateinit var presenter: IRecorderContract.Presenter
    private lateinit var handler: Handler
    // 是否正在播放
    private var isPlay = false

    private val focusAni:AnimatorSet by lazy { initvideo_record_focusAni() }
    private val showSendAni:AnimatorSet by lazy { initShowSendViewAni() }
    private val hideSendAni:AnimatorSet by lazy { initHideShowViewAni() }
    private val runnable: Runnable by lazy { initRunnable() }

    companion object {
        private const val RESULT_FILEPATH = "result_filepath"
        private const val ARG_FILEPATH = "arg_filepath"
        @JvmStatic
        fun newIntent(ctx: Context, filePath: String): Intent =
                Intent(ctx, Recorder::class.java)
                        .putExtra(ARG_FILEPATH, filePath)

        @JvmStatic
        fun getResult(intent: Intent): String =
                intent.getStringExtra(RESULT_FILEPATH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,  WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_recorder)
        val filePath = intent.getStringExtra(ARG_FILEPATH)
        handler = Handler()
        presenter = RecorderPresenter()
        presenter.attach(this, filePath)

        // 先让video_record_focus渲染一次，下面要用到其宽高
        focusAni.start()

        recorder_back.setOnClickListener(this)
        recorder_cancelbtn.setOnClickListener(this)
        recorder_certainbtn.setOnClickListener(this)

        recorder_vv.setOnTouchListener(svOnTouched)
        recorder_progress.setListener(this)
        recorder_progress.setOnTouchListener(progressOnTouched)
        recorder_vv.setOnPreparedListener(this)
    }

    override fun onResume() {
        super.onResume()
        initCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.detach()
    }

    private fun initCamera() {
        recorder_vv.viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                recorder_vv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                presenter.initCamera(recorder_vv.holder, recorder_vv.width, recorder_vv.height)
            }
        })
    }

    override fun startRecord() {
        Log.d(TAG, "videoview width: ${recorder_vv.width}, height: ${recorder_vv.height}")
        recorder_progress.recording()
    }

    override fun updateProgress(p: Int) {
        recorder_progress.setProgress(p)
    }

    override fun recordComplete(p: Int) {
        Log.d(TAG, "$p")
        recorder_progress.recordComplete()
        showSendView()
    }

    private fun showvideo_record_focus(x: Float, y: Float) {
        Log.d(TAG, "focus x: $x, focus y: $y")
        Log.d(TAG, "focus Width: ${recorder_focus.width}, focus Height: ${recorder_focus.height}")
        recorder_focus.x = x - recorder_focus.width / 2
        recorder_focus.y = y - recorder_focus.height / 2
        if (focusAni.isRunning) {
            focusAni.cancel()
        }
        focusAni.start()
        presenter.handleFocusMetering(x, y)
    }

    private fun showSendView() {
        if (showSendAni.isRunning) {
            showSendAni.cancel()
        }
        showSendAni.start()
    }

    private fun hideSendView() {
        if (hideSendAni.isRunning) {
            hideSendAni.cancel()
        }
        hideSendAni.start()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.recorder_back -> finish()
            R.id.recorder_cancelbtn -> {
                hideSendView()
                presenter.reconnect(recorder_vv.holder, recorder_vv.width, recorder_vv.height)
            }
        }
    }

    override fun playVideo(filePath: String) {
        recorder_vv.setVideoPath(filePath)
        recorder_vv.start()
        recorder_vv.setOnPreparedListener(this)
    }

    override fun stopVideo() {
        recorder_vv.stopPlayback()
        isPlay = false
    }

    override fun onPrepared(mp: MediaPlayer) {
        isPlay = true
        mp.start()
        mp.isLooping = true
    }

    override fun touched() {
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    override fun untouched() {
        recorder_progress.setProgress(0)
        handler.removeCallbacks(runnable)
    }

    private fun initRunnable() = Runnable {
        presenter.recording()
        handler.postDelayed(runnable, 100)
    }

    private val svOnTouched = View.OnTouchListener { _, event ->
        Log.d(TAG, "videoview touched x: ${event.x}, y: ${event.y}")
        showvideo_record_focus(event.x, event.y)
        false
    }

    private val progressOnTouched = View.OnTouchListener { v, event -> presenter.onTouch(v, event); true }

    private fun initvideo_record_focusAni(): AnimatorSet {
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

    private fun initShowSendViewAni(): AnimatorSet {
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

    private fun initHideShowViewAni(): AnimatorSet {
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