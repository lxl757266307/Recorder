package com.kanade.recorder

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.WindowManager
import android.widget.Toast
import com.kanade.recorder.camera1.Camera1Fragment
import com.kanade.recorder.camera2.Camera2Fragment
import android.content.ContextWrapper



class Recorder : AppCompatActivity() {
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA)

    companion object {
        private const val PERMISSION_CODE = 101
        const val ARG_RESULT = "arg_result"
        const val ARG_FILEPATH = "arg_filepath"
        const val DURATION_LIMIT = 10

        @JvmStatic
        fun newIntent(context: Context, filepath: String): Intent {
            val intent = Intent(context, Recorder::class.java)
            intent.putExtra(ARG_FILEPATH, filepath)
            return intent
        }

        @JvmStatic
        fun getResult(intent: Intent): RecorderResult =
                intent.getParcelableExtra(ARG_RESULT)
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(object : ContextWrapper(newBase) {
            override fun getSystemService(name: String) =
                    if (Context.AUDIO_SERVICE == name) {
                        applicationContext.getSystemService(name)
                    } else {
                        super.getSystemService(name)
                    }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_recorder)

        if (Build.VERSION.SDK_INT >= 23) {
            for (permission in permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                    continue
                }

                if (!shouldShowRequestPermissionRationale(permission)) {
                    showPermissionRequestDialog()
                } else {
                    showDenied()
                    finish()
                }
                return
            }
            init()
        } else {
            init()
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_CODE) {
            val granted = grantResults
                    .filter { it == PackageManager.PERMISSION_GRANTED }
                    .size
            if (granted == permissions.size) {
                init()
            } else {
                showDenied()
                finish()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun showPermissionRequestDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.permission_recorder_rationale)
                .setPositiveButton(R.string.button_allow, { _, _ -> requestPermissions(permissions, PERMISSION_CODE) })
                .setNegativeButton(R.string.button_deny, { _, _ ->
                    showDenied()
                    finish()
                })
                .show()
    }

    private fun showDenied() {
        Toast.makeText(this, R.string.permission_recorder_denied, Toast.LENGTH_LONG).show()
    }

    private fun init() {
        val filepath = intent.getStringExtra(ARG_FILEPATH)
        val fm = supportFragmentManager
        // 默认启动录像fragment
        if (fm.backStackEntryCount <= 0) {
            val transaction = fm.beginTransaction()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 检查对camera2的支持程度
                val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = manager.cameraIdList[0]
                val characteristics = manager.getCameraCharacteristics(cameraId) as CameraCharacteristics
                val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                if (hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) {
                    transaction.add(R.id.recorder_fl, Camera2Fragment.newInstance(filepath))
                } else {
                    transaction.add(R.id.recorder_fl, Camera1Fragment.newInstance(filepath))
                }
            } else {
                transaction.add(R.id.recorder_fl, Camera1Fragment.newInstance(filepath))
            }
            transaction.commitAllowingStateLoss()
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