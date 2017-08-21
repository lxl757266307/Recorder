package com.kanade.recorder

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.widget.Toast
import com.kanade.recorder.camera1.Camera1Fragment
import com.kanade.recorder.camera2.Camera2Fragment

class RecorderActivity : AppCompatActivity() {
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)

    companion object {
        private const val PERMISSION_CODE = 101;
        const val RESULT_FILEPATH = "result_filepath"
        const val ARG_FILEPATH = "arg_filepath"
        const val ARG_DURATION = "arg_duration"
        const val MAX_DURATION = 10

        @JvmStatic
        fun newInstance(context: Context, filepath: String): Intent {
            val intent = Intent(context, RecorderActivity::class.java)
            intent.putExtra(ARG_FILEPATH, filepath)
            return intent
        }

        @JvmStatic
        fun getResult(intent: Intent): RecorderResult =
                intent.getParcelableExtra(RESULT_FILEPATH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recorder)

        // 权限检查
        if (Build.VERSION.SDK_INT >= 23) {
            for (permission in permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                    continue
                }

                if (!shouldShowRequestPermissionRationale(permission)) {
                    showPermissionRequestDialog()
                } else {
                    showDenied()
                }
                return
            }
        }
        init()
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                init()
            } else {
                showDenied()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun showPermissionRequestDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.permission_recorder_rationale)
                .setPositiveButton(R.string.button_allow, { _, _ -> requestPermissions(permissions, PERMISSION_CODE) })
                .setNegativeButton(R.string.button_deny, { _, _ -> showDenied() })
                .show()
    }

    private fun showDenied() {
        Toast.makeText(this, R.string.permission_recorder_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun init() {
        val filepath = intent.getStringExtra(ARG_FILEPATH)
        val fm = supportFragmentManager
        val isLollipop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        // 默认启动录像fragment
        if (fm.backStackEntryCount <= 0) {
            val transaction = fm.beginTransaction()
            if (isLollipop) {
                transaction.replace(R.id.recorder_fl, Camera2Fragment.newInstance(filepath))
            } else {
                transaction.replace(R.id.recorder_fl, Camera1Fragment.newInstance(filepath))
            }
            transaction.addToBackStack(null)
                    .commit()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        val fm = supportFragmentManager
        if (fm.backStackEntryCount < 1) {
            finish()
        }
    }
}