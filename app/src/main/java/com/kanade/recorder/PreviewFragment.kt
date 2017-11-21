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
        fun newInstance(result: RecorderResult): PreviewFragment {
            val args = Bundle()
            args.putParcelable(Recorder.ARG_RESULT, result)

            val fragment = PreviewFragment()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var cancelBtn: ImageView
    private lateinit var positiveBtn: ImageView
    private lateinit var videoView: VideoView

    private lateinit var result: RecorderResult

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_preview, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        videoView = view.findViewById(R.id.fg_pv_vv)
        cancelBtn = view.findViewById(R.id.fg_pv_cancel)
        positiveBtn = view.findViewById(R.id.fg_pv_positive)

        showOrHideBtnAni(0f, 1f)
        cancelBtn.setOnClickListener(this)
        positiveBtn.setOnClickListener(this)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        result = arguments.getParcelable(Recorder.ARG_RESULT)
        startVideo(result.filepath)
    }

    private fun startVideo(filepath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // 避免播放前的短暂黑屏
            videoView.setOnInfoListener(this)
        } else {
            videoView.setBackgroundColor(Color.TRANSPARENT)
        }
        videoView.setOnPreparedListener(this)
        videoView.setVideoPath(filepath)
        videoView.start()
    }

    override fun onDestroyView() {
        videoView.stopPlayback()
        super.onDestroyView()
    }

    override fun onPrepared(mp: MediaPlayer) {
        mp.isLooping = true
        mp.start()
    }

    override fun onInfo(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
            videoView.setBackgroundColor(Color.TRANSPARENT)
        }
        return true
    }

    override fun onClick(v: View) {
        if (v.id == R.id.fg_pv_positive) {
            val data = Intent()
            data.putExtra(Recorder.ARG_RESULT, result)
            activity.setResult(AppCompatActivity.RESULT_OK, data)
        } else {
            val file = File(result.filepath)
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