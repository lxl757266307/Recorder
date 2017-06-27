package com.kanade.demo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import com.kanade.recorder.Recorder
import com.kanade.recorder.Recorder2
import kotlinx.android.synthetic.main.activity_main.*
import permissions.dispatcher.*
import java.io.File

@RuntimePermissions
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private val RESULT = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recorder.setOnClickListener(this)
        MainActivityPermissionsDispatcher.openRecorderWithCheck(this)
    }

    override fun onClick(v: View?) {
        openRecorder()
    }

    @NeedsPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun openRecorder() {
        val dir = Environment.getExternalStorageDirectory().toString() + File.separator + "recorder"
        val dirFile = File(dir)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }

        val file = "video_" + System.currentTimeMillis() + ".mp4"
        val intent = Recorder2.newIntent(this, dir + File.separator + file)
        startActivityForResult(intent, RESULT)
    }

    @OnShowRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun showRationaleForWrite(request: PermissionRequest) {
        AlertDialog.Builder(this)
                .setMessage(R.string.permission_request)
                .setPositiveButton(R.string.button_allow, { _, _ -> request.proceed() })
                .setNegativeButton(R.string.button_deny, { _, _ -> request.cancel(); finish() })
                .show()
    }

    @OnPermissionDenied(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun showDeniedForWrite() {
        Toast.makeText(this, com.kanade.recorder.R.string.permission_recorder_denied, Toast.LENGTH_SHORT).show()
        finish()
    }

    @OnNeverAskAgain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    fun showNeverAskForWrite() {
        Toast.makeText(this, com.kanade.recorder.R.string.permission_recorder_denied, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults)
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