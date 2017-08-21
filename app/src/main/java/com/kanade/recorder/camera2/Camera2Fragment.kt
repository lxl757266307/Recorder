package com.kanade.recorder.camera2

import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.kanade.recorder.R
import com.kanade.recorder.RecorderActivity
import com.kanade.recorder.widget.AutoFitTextureView
import com.kanade.recorder.widget.VideoProgressBtn

/**
 * Created by kanade on 2017/8/21.
 */
class Camera2Fragment : Fragment() {
    companion object {
        @JvmStatic
        fun newInstance(filepath: String): Camera2Fragment {
            val args = Bundle()
            args.putString(RecorderActivity.ARG_FILEPATH, filepath)

            val fragment = Camera2Fragment()
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var textureView: AutoFitTextureView
    private lateinit var progressBtn: VideoProgressBtn
    private lateinit var focusBtn: ImageView
    private lateinit var backBtn: ImageView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_camera2, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        textureView = view.findViewById(R.id.recorder_tv) as AutoFitTextureView
    }
}