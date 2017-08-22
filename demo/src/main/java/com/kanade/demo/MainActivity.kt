package com.kanade.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.kanade.recorder.Recorder
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val RESULT = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recorder.setOnClickListener(this)
    }

    override fun onClick(v: View?) {
        openRecorder()
    }

    fun openRecorder() {
        val dir = Environment.getExternalStorageDirectory().toString() + File.separator + "recorder"
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val file = "video_" + System.currentTimeMillis() + ".mp4"
        val intent = Recorder.newInstance(this, dir + File.separator + file)
        startActivityForResult(intent, RESULT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) {
            recorder_result.text = getString(R.string.no_record)
            return
        }

        if (requestCode == RESULT) {
            val result = Recorder.getResult(data)
            val file = File(result.filepath)
            val size = file.length()
            recorder_result.text = getString(R.string.success, result.filepath, size, result.duration)
        }
    }
}