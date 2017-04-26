package com.kanade.demo

import android.os.Bundle
import android.os.Environment
import android.support.v7.app.AppCompatActivity
import com.kanade.recorder.Recorder
import java.io.File

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val dir = Environment.getExternalStorageDirectory().toString() + File.separator + "recorder"
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val file = "video_" + System.currentTimeMillis() + ".mp4"
        val intent = Recorder.newIntent(this, dir + File.separator + file)
        startActivity(intent)
    }
}