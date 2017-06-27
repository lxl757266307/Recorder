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
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder.Utils.MediaRecorderManager
import com.kanade.recorder.widget.VideoProgressBtn
import kotlinx.android.synthetic.main.activity_recorder.*
import permissions.dispatcher.*
import java.io.File

@RuntimePermissions
abstract class Recorder : AppCompatActivity(), VideoProgressBtn.AniEndListener, View.OnClickListener, MediaPlayer.OnPreparedListener, MediaRecorderManager.MediaStateListener {

    protected lateinit var handler: Handler
    // 录像文件保存位置
    protected lateinit var filePath: String
    protected lateinit var mediaRecorderManager: MediaRecorderManager
    // 录像时记录已录制时长(用于限制最大录制时间)
    protected var duration = 0f
    protected var isRecording = false
    // 是否正在播放
    protected var isPlaying = false

    private val scaleGestureListener by lazy { initScaleGestureListener() }

    private val runnable: Runnable by lazy { initRunnable() }
    private val focusAni by lazy { initFocusViewAni() }
    private val showCompleteAni by lazy { initShowCompleteViewAni() }
    private val hideCompleteAni by lazy { initHideCompleteViewAni() }


    companion object {
        // 允许录像最大时长
        private const val MAX_DURATION = 10
        private const val RESULT_FILEPATH = "result_filepath"
        private const val ARG_FILEPATH = "arg_filepath"

        @JvmStatic
        fun newIntent(ctx: Context, filePath: String): Intent {
            return Intent(ctx, Recorder1::class.java)
                    .putExtra(ARG_FILEPATH, filePath)
        }

        @JvmStatic
        fun getResult(intent: Intent): RecorderResult =
                intent.getParcelableExtra(RESULT_FILEPATH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)
        filePath = intent.getStringExtra(ARG_FILEPATH)
        mediaRecorderManager = MediaRecorderManager()

        BaseActivityPermissionsDispatcher.checkPermissionWithCheck(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放所有资源
        stopVideo()
        mediaRecorderManager.releaseMediaRecorder()
    }

    protected fun startFocusAni() {
        if (focusAni.isRunning) {
            focusAni.cancel()
        }
        focusAni.start()
    }

    protected fun startShowCompleteAni() {
        if (showCompleteAni.isRunning) {
            showCompleteAni.cancel()
        }
        showCompleteAni.start()
    }

    protected fun startHideCompleteView() {
        if (hideCompleteAni.isRunning) {
            hideCompleteAni.cancel()
        }
        hideCompleteAni.start()
    }

    override fun touched() {
        duration = 0f
        isRecording = true
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    override fun untouched() {
        // 当手指放开即马上重置按钮的进度条，并停止runnable(避免录音时长仍在增加)
        recorder_progress.setProgress(0)
        handler.removeCallbacks(runnable)

        // 检查录音时长是否超时
        if (isRecording) {
            isRecording = false
            mediaRecorderManager.stopRecord()
            val sec = duration / 10.0
            if (sec < 1) {
                Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_LONG).show()
            } else {
                recordComplete()
            }
        }
    }

    /**
     * 递增duration，并更新按钮的进度条
     *
     * 当录制时间超过最大时长时，强制停止
     */
    protected fun initRunnable() = Runnable {
        duration++
        val sec = ((duration / 10.0) / MAX_DURATION * 100).toInt()
        recorder_progress.setProgress(sec)
        if (sec > MAX_DURATION) {
            recordComplete()
            return@Runnable
        }
        handler.postDelayed(runnable, 100)
    }

    /**
     * 播放录制的视频
     *
     * 在播放之前需要先关闭摄像头的预览画面
     */
    protected fun playVideo(filePath: String) {
        if (isPlaying) return
        recorder_vv.setVideoPath(filePath)
        recorder_vv.start()
    }

    protected fun stopVideo() {
        if (!isPlaying) return
        isPlaying = false
        recorder_vv.stopPlayback()
    }

    /**
     * 需要播放的录像文件已准备好
     */
    override fun onPrepared(mp: MediaPlayer) {
        isPlaying = true
        mp.isLooping = true
        mp.start()
    }

    /**
     * 停止视频播放，并删除视频文件(为新的视频录制文件腾出位置)
     *
     * 并再次启动预览
     */
    protected fun cancelButton() {
        stopVideo()
        File(filePath).run { if (exists()) delete() }
        startHideCompleteView()
        startPreview()
    }

    /**
     * 注意播放视频前要释放camera
     */
    open fun recordComplete() {
        recorder_progress.recordComplete()
        // 隐藏"录像"和"返回"按钮，显示"取消"和"确认"按钮，并播放已录制的视频
        startShowCompleteAni()
        playVideo(filePath)
    }

    open fun startPreview() = Unit

    open fun zoom(zoom: Int) = Unit

    open fun zoom(isZoom: Boolean) = Unit

    open fun focus(x: Float, y: Float) = Unit

    @NeedsPermission(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    open fun checkPermission() {
        handler = Handler()
        // 先让video_record_focus渲染一次，下面要用到其宽高
        startFocusAni()
        recorder_vv.setOnPreparedListener(this)
        recorder_vv.setOnTouchListener(vvOnTouched)

        recorder_back.setOnClickListener(this)
        recorder_cancelbtn.setOnClickListener(this)
        recorder_positivebtn.setOnClickListener(this)

        recorder_progress.setListener(this)
        recorder_progress.setOnTouchListener(progressOnTouched())

        mediaRecorderManager.setListener(this)
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.recorder_back -> finish()
            R.id.recorder_cancelbtn -> cancelButton()
            R.id.recorder_positivebtn -> {
                val result = RecorderResult(filePath, (duration / 10.0).toInt())
                val data = Intent()
                data.putExtra(RESULT_FILEPATH, result)
                setResult(RESULT_OK, data)
                finish()
            }
        }
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
        BaseActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults)
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    private fun initFocusViewAni(): AnimatorSet =
            AnimatorSet {
                setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        super.onAnimationStart(animation)
                        recorder_focus.alpha = 1f
                        recorder_focus.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        recorder_focus.visibility = View.GONE
                    }
                })
                invoke {
                    play(ObjectAnimator.ofFloat(recorder_focus, "scaleX", 1.5f, 1f).apply { duration = 500 })
                            .with(ObjectAnimator.ofFloat(recorder_focus, "scaleY", 1.5f, 1f).apply { duration = 500 })
                            .before(ObjectAnimator.ofFloat(recorder_focus, "alpha", 1f, 0f).apply { duration = 750 })
                }
            }

    private fun initShowCompleteViewAni(): AnimatorSet =
            AnimatorSet {
                duration = 350
                setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        super.onAnimationStart(animation)
                        recorder_back.visibility = View.GONE
                        recorder_cancelbtn.visibility = View.VISIBLE
                        recorder_positivebtn.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        recorder_progress.visibility = View.GONE
                    }
                })
                scaleAnis(recorder_cancelbtn, 0f, 1f)
                scaleAnis(recorder_positivebtn, 0f, 1f)
                scaleAnis(recorder_progress, 1f, 0f)
            }

    private fun initHideCompleteViewAni(): AnimatorSet =
            AnimatorSet {
                duration = 350
                setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        recorder_progress.visibility = View.VISIBLE
                        recorder_back.visibility = View.VISIBLE
                        recorder_cancelbtn.visibility = View.GONE
                        recorder_positivebtn.visibility = View.GONE
                    }
                })
                scaleAnis(recorder_cancelbtn, 1f, 0f)
                scaleAnis(recorder_positivebtn, 1f, 0f)
                scaleAnis(recorder_progress, 0f, 1f)
            }

    private inner class progressOnTouched : View.OnTouchListener {
        private var firstTouchY = 0f
        private var touchSlop = 0f

        init {
            val configuration = ViewConfiguration.get(this@Recorder)
            touchSlop = configuration.scaledTouchSlop.toFloat()
        }

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    firstTouchY = recorder_vv.y
                    recorder_progress.startRecord()
                }
            // 向下手势滑动，和向上手势滑动距离过短也不触发缩放事件
                MotionEvent.ACTION_MOVE -> {
                    if (event.y >= recorder_vv.y) return true
                    val distance = firstTouchY - event.y
                    zoom((distance / 10).toInt())
                }
                MotionEvent.ACTION_UP -> {
                    recorder_progress.recordComplete()
                }
            }
            return true
        }
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
                override fun onSingleTap(event: MotionEvent) {
                    // 录像按钮以下位置不允许对焦
                    if (event.y < recorder_progress.y) {
                        val x = event.x
                        val y = event.y
//                        Log.d(TAG, "focus x: $x, focus y: $y")
//                        Log.d(TAG, "focus Width: ${recorder_focus_btn.width}, focus Height: ${recorder_focus_btn.height}")
                        recorder_focus.x = x - recorder_focus.width / 2
                        recorder_focus.y = y - recorder_focus.height / 2
                        startFocusAni()
                        focus(x, y)
                    }
                }

                override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) = zoom(scaleFactor > 1f)
            })
}