package com.kanade.recorder

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_recorder.*
import android.view.*
import com.kanade.recorder.GestureImpl.ScaleGestureImpl
import com.kanade.recorder._interface.IRecorderContract
import android.util.DisplayMetrics

class Recorder : BaseActivity(), IRecorderContract.View, MediaPlayer.OnPreparedListener {
    private val TAG = "Recorder"
    private lateinit var presenter: IRecorderContract.Presenter

    private val scaleGestureListener by lazy { initScaleGestureListener() }

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
        val filePath = intent.getStringExtra(ARG_FILEPATH)
        presenter = RecorderPresenter()
        presenter.attach(this, filePath)

        BaseActivityPermissionsDispatcher.checkPermissionWithCheck(this)
    }

    override fun checkPermission() {
        super.checkPermission()
        recorder_vv.setOnPreparedListener(this)
        recorder_vv.setOnTouchListener(vvOnTouched)

        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)

        presenter.init(recorder_vv.holder, dm.widthPixels, dm.heightPixels)
        presenter.startPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.detach()
    }

    override fun notice(content: String) {
        Toast.makeText(this, content, Toast.LENGTH_LONG).show()
    }

    override fun updateProgress(p: Int) {
        recorder_progress.setProgress(p)
    }

    override fun recordComplete() {
        recorder_progress.recordComplete()
        startShowCompleteAni()
    }

    private fun focus(x: Float, y: Float) {
//        Log.d(TAG, "focus x: $x, focus y: $y")
//        Log.d(TAG, "focus Width: ${recorder_focus_btn.width}, focus Height: ${recorder_focus_btn.height}")
        recorder_focus.x = x - recorder_focus.width / 2
        recorder_focus.y = y - recorder_focus.height / 2
        startFocusAni()
        presenter.handleFocusMetering(x, y)
    }

    override fun cancelButton() {
        super.cancelButton()
        presenter.reconnect()
    }

    override fun positiveButton() {
        super.positiveButton()
        presenter.setResult()
    }

    override fun zoom(zoom: Int) {
        super.zoom(zoom)
        presenter.handleZoom(zoom)
    }

    override fun getSurfaceY(): Float {
        return recorder_vv.y
    }

    override fun setResult(result: RecorderResult) {
        val data = Intent()
        data.putExtra(RESULT_FILEPATH, result)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun playVideo(filePath: String) {
        if (recorder_vv.isPlaying) return
        recorder_vv.setVideoPath(filePath)
    }

    override fun stopVideo() {
        if (!recorder_vv.isPlaying) return
        recorder_vv.stopPlayback()
    }

    override fun onPrepared(mp: MediaPlayer) {
        mp.isLooping = true
        mp.start()
    }

    override fun touched() {
        super.touched()
        presenter.staretRecord()
    }

    override fun untouched() {
        super.untouched()
        presenter.recordComplete()
    }

    override fun initRunnable(): Runnable {
        presenter.recording()
        return super.initRunnable()
    }

    /**
     * videoview触摸事件，将会处理两种事件: 双指缩放，单击对焦
     *
     * 当正处于播放录像的时候将不会进行处理
     */
    private val vvOnTouched = View.OnTouchListener { _, event ->
        if (recorder_vv.isPlaying) return@OnTouchListener true
        return@OnTouchListener scaleGestureListener.onTouched(event)
    }

    private fun initScaleGestureListener(): ScaleGestureImpl =
            ScaleGestureImpl(this, object : ScaleGestureImpl.GestureListener {
                override fun onSingleTap(event: MotionEvent) {
                    // 录像按钮以下位置不允许对焦
                    if (event.y < recorder_progress.y) focus(event.x, event.y)
                }
                override fun onScale(scaleFactor: Float, focusX: Float, focusY: Float) = presenter.handleZoom(scaleFactor > 1f)
            })

    override fun getContext(): Context = getContext()
}