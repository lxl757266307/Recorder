package com.kanade.recorder

import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import com.kanade.recorder.camera1.Camera1Fragment
import com.kanade.recorder.camera2.Camera2Fragment

/**
 * preview the recorded video
 *
 * Created by kanade on 2017/8/21.
 */
class PreviewFragment : Fragment(), View.OnClickListener, MediaPlayer.OnPreparedListener {
    companion object {
        fun newInstance(filepath: String, duration: Int): PreviewFragment {
            val args = Bundle()
            args.putString(RecorderActivity.ARG_FILEPATH, filepath)
            args.putInt(RecorderActivity.ARG_DURATION, duration)

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
        videoview = view.findViewById(R.id.fg_pv_vv) as VideoView
        cancelBtn = view.findViewById(R.id.fg_pv_cancel) as ImageView
        positiveBtn = view.findViewById(R.id.fg_pv_positive) as ImageView

        cancelBtn.setOnClickListener(this)
        positiveBtn.setOnClickListener(this)
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        duration = arguments.getInt(RecorderActivity.ARG_DURATION)
        filepath = arguments.getString(RecorderActivity.ARG_FILEPATH)

        videoview.setOnPreparedListener(this)
        videoview.setVideoPath(filepath)
        videoview.start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoview.stopPlayback()
    }

    override fun onPrepared(mp: MediaPlayer) {
        mp.isLooping = true
        mp.start()
    }

    override fun onClick(v: View) {
        if (v.id == R.id.fg_pv_cancel) {
            val isLollipop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            val fg: Fragment = if (isLollipop) {
                Camera2Fragment.newInstance(filepath)
            } else {
                Camera1Fragment.newInstance(filepath)
            }
            activity.supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.recorder_fl, fg)
                    .addToBackStack(null)
                    .commit()
        } else if (v.id == R.id.fg_pv_positive) {
            val result = RecorderResult(filepath, (duration / 10.0).toInt())
            val data = Intent()
            data.putExtra(RecorderActivity.RESULT_FILEPATH, result)
            activity.setResult(AppCompatActivity.RESULT_OK, data)
            activity.finish()
        }
    }
}