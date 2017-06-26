package com.kanade.recorder

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Handler
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import com.kanade.recorder.widget.VideoProgressBtn
import kotlinx.android.synthetic.main.activity_recorder.*
import permissions.dispatcher.*

@RuntimePermissions
abstract class BaseActivity : AppCompatActivity(), VideoProgressBtn.AniEndListener, View.OnClickListener {
    protected lateinit var handler: Handler
    private val runnable: Runnable by lazy { initRunnable() }
    // 是否正在播放
    protected var isPlaying = false

    private val focusAni by lazy { initFocusViewAni() }
    private val showCompleteAni by lazy { initShowCompleteViewAni() }
    private val hideCompleteAni by lazy { initHideCompleteViewAni() }

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
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    override fun untouched() {
        recorder_progress.setProgress(0)
        handler.removeCallbacks(runnable)
    }

    protected open fun initRunnable() = Runnable {
        handler.postDelayed(runnable, 100)
    }

    open fun cancelButton() {
        startHideCompleteView()
    }

    open fun positiveButton() {}

    open fun zoom(zoom: Int) {}

    open fun getSurfaceY(): Float { return 0f }

    @NeedsPermission(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)
    open fun checkPermission() {
        handler = Handler()
        // 先让video_record_focus渲染一次，下面要用到其宽高
        startFocusAni()

        recorder_back.setOnClickListener(this)
        recorder_cancelbtn.setOnClickListener(this)
        recorder_positivebtn.setOnClickListener(this)

        recorder_progress.setListener(this)
        recorder_progress.setOnTouchListener(progressOnTouched())
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.recorder_back -> finish()
            R.id.recorder_cancelbtn -> cancelButton()
            R.id.recorder_positivebtn -> positiveButton()
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
                listener = object : AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: Animator?) {
                        super.onAnimationStart(animation)
                        recorder_focus.alpha = 1f
                        recorder_focus.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        recorder_focus.visibility = View.GONE
                    }
                }
                invoke {
                    play(ObjectAnimator.ofFloat(recorder_focus, "scaleX", 1.5f, 1f).apply { duration = 500 })
                            .with(ObjectAnimator.ofFloat(recorder_focus, "scaleXY", 1.5f, 1f).apply { duration = 500 })
                            .before(ObjectAnimator.ofFloat(recorder_focus, "alpha", 1f, 0f).apply { duration = 750 })
                }
            }

    private fun initShowCompleteViewAni(): AnimatorSet =
            AnimatorSet {
                duration = 350
                listener = object : AnimatorListenerAdapter() {
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
                }
                scaleAnis(recorder_cancelbtn, 0f, 1f)
                scaleAnis(recorder_positivebtn, 0f, 1f)
                scaleAnis(recorder_progress, 1f, 0f)
            }

    private fun initHideCompleteViewAni(): AnimatorSet =
            AnimatorSet {
                duration = 350
                listener = object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        super.onAnimationEnd(animation)
                        recorder_progress.visibility = View.VISIBLE
                        recorder_back.visibility = View.VISIBLE
                        recorder_cancelbtn.visibility = View.GONE
                        recorder_positivebtn.visibility = View.GONE
                    }
                }
                scaleAnis(recorder_cancelbtn, 1f, 0f)
                scaleAnis(recorder_positivebtn, 1f, 0f)
                scaleAnis(recorder_progress, 0f, 1f)
            }

    private inner class progressOnTouched : View.OnTouchListener {
        private var firstTouchY = 0f
        private var touchSlop = 0f
        init {
            val configuration = ViewConfiguration.get(this@BaseActivity)
            touchSlop = configuration.scaledTouchSlop.toFloat()
        }
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val y = getSurfaceY()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    firstTouchY = y
                    recorder_progress.startRecord()
                }
                // 向下手势滑动，和向上手势滑动距离过短也不触发缩放事件
                MotionEvent.ACTION_MOVE -> {
                    if (event.y >= y) return true
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
}