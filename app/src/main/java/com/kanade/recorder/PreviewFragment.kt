package com.kanade.recorder

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.*
import android.widget.ImageView
import android.widget.VideoView
import java.io.File

/**
 * preview the recorded video
 *
 * Created by kanade on 2017/8/21.
 */
class PreviewFragment : Fragment(), View.OnClickListener, MediaPlayer.OnPreparedListener, MediaPlayer.OnInfoListener {
    companion object {
        fun newInstance(filepath: String, duration: Int): PreviewFragment {
            val args = Bundle()
            args.putString(Recorder.ARG_FILEPATH, filepath)
            args.putInt(Recorder.ARG_DURATION, duration)

            val fragment = PreviewFragment()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var cancelBtn: ImageView
    private lateinit var positiveBtn: ImageView
    private lateinit var videoview: VideoView

    private var duration = 0
    private lateinit var filepath: String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_preview, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        videoview = view.findViewById(R.id.fg_pv_vv)
        cancelBtn = view.findViewById(R.id.fg_pv_cancel)
        positiveBtn = view.findViewById(R.id.fg_pv_positive)


        showOrHideBtnAni(0f, 1f)
        cancelBtn.setOnClickListener(this)
        positiveBtn.setOnClickListener(this)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        duration = arguments.getInt(Recorder.ARG_DURATION)
        filepath = arguments.getString(Recorder.ARG_FILEPATH)
        startVideo(filepath)
    }

    private fun startVideo(filepath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // 避免播放前的短暂黑屏
            videoview.setOnInfoListener(this)
        } else {
            videoview.setBackgroundColor(Color.TRANSPARENT)
        }

        videoview.setOnPreparedListener(this)
        videoview.setVideoPath(filepath)
        videoview.start()
    }

    override fun onDestroyView() {
        videoview.stopPlayback()
        super.onDestroyView()
    }

    override fun onPrepared(mp: MediaPlayer) {
        mp.isLooping = true
        mp.start()
    }

    override fun onInfo(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
            videoview.setBackgroundColor(Color.TRANSPARENT)
        }
        return true
    }

    override fun onClick(v: View) {
        if (v.id == R.id.fg_pv_positive) {
            val result = RecorderResult(filepath, (duration / 10.0).toInt())
            val data = Intent()
            data.putExtra(Recorder.RESULT_FILEPATH, result)
            activity.setResult(AppCompatActivity.RESULT_OK, data)
        } else {
            val file = File(filepath)
            file.delete()
        }
        activity.finish()
    }

    private fun showOrHideBtnAni(from: Float, to: Float) {
        AnimatorSet().apply {
            duration = 500
            playTogether(ObjectAnimator.ofFloat(cancelBtn, "scaleX", from, to), ObjectAnimator.ofFloat(cancelBtn, "scaleY", from, to),
                    ObjectAnimator.ofFloat(positiveBtn, "scaleX", from, to), ObjectAnimator.ofFloat(positiveBtn, "scaleY", from, to))
        }.start()
    }
}