package com.kanade.recorder.Utils

import android.annotation.TargetApi
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface

class MediaRecorderManager {
    private val TAG = "MediaRecorderManager"
    private var isPrepare = false
    private var isRecording = false
    private var recorder: MediaRecorder? = null

    private var listener: MediaStateListener? = null

    fun setListener(listener: MediaStateListener) {
        this.listener = listener
    }

    fun getRecorder(): MediaRecorder? = recorder

    fun isPrepare() = isPrepare

    fun isRecording() = isRecording

    fun startRecord() {
        if (isRecording) {
            stopRecord()
        } else if (isPrepare) {
            Log.d(TAG, "record started")
            try {
                recorder?.start()
                isRecording = true
            } catch (r: RuntimeException) {
                releaseMediaRecorder()
                Log.d(TAG, "RuntimeException: start() is called immediately after stop()")
                r.printStackTrace()
            }
        }
    }

    fun stopRecord() {
        if (isRecording) {
            isRecording = false
            isPrepare = false
            try {
                recorder?.stop()
            } catch (r: RuntimeException) {
                recorder?.reset()
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()")
                r.printStackTrace()
            } finally {
                releaseMediaRecorder()
            }
        } else if (isPrepare) {
            isPrepare = false
            releaseMediaRecorder()
        }
    }

    fun releaseMediaRecorder() {
        recorder?.let { recorder ->
            recorder.reset()
            recorder.release()
            listener?.release(recorder)
        }
        recorder = null
    }

    fun prepareRecord(profile: CamcorderProfile, filePath: String): Boolean {
        try {
            recorder = MediaRecorder()
            recorder?.let { recorder ->
                listener?.prepare(recorder)
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
//                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
//                    recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
//                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                } else {
                    recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                }
                recorder.setOrientationHint(90)
                recorder.setProfile(profile)
                recorder.setOutputFile(filePath)
                recorder.prepare()
                isPrepare = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Exception prepareRecord: ")
            releaseMediaRecorder()
            return false
        }
        return true
    }

    interface MediaStateListener {
        fun prepare(recorder: MediaRecorder)

        fun release(recorder: MediaRecorder)
    }
}