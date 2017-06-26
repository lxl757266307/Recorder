package com.kanade.recorder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_recorder.*
import com.kanade.recorder._interface.IRecorderContract
import android.util.DisplayMetrics

class Recorder : BaseActivity(), IRecorderContract.View {
    private val TAG = "Recorder"
    private lateinit var presenter: IRecorderContract.Presenter

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

    override fun focus(x: Float, y: Float) {
        super.focus(x, y)
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

    override fun zoom(isZoom: Boolean) {
        super.zoom(isZoom)
        presenter.handleZoom(isZoom)
    }

    override fun setResult(result: RecorderResult) {
        val data = Intent()
        data.putExtra(RESULT_FILEPATH, result)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun playVideo(filePath: String) {
        play(filePath)
    }

    override fun stopVideo() {
        stopPlay()
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

    override fun getContext(): Context = getContext()
}