package com.kanade.recorder

import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Log

class MediaRecorderManager {
    private val TAG = "MediaRecorderManager"
    private var isRecording = false
    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null

    fun record(camera: Camera, profile: CamcorderProfile, filePath: String) {
        this.camera = camera
        if (isRecording) {
            try {
                recorder?.stop()  // stop the recording
            } catch (e: RuntimeException) {
                // RuntimeException is thrown when stop() is called immediately after start().
                // In this case the output file is not properly constructed ans should be deleted.
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()")
            }

            releaseMediaRecorder() // release the MediaRecorder object
            isRecording = false
        } else if (prepareRecord(camera, profile, filePath)) {
            try {
                recorder?.start()
                isRecording = true
            } catch (r: RuntimeException) {
                releaseMediaRecorder()
                Log.d(TAG, "RuntimeException: start() is called immediately after stop()")
            }
        }
    }

    fun stopRecord() {
        if (isRecording) {
            isRecording = false
            try {
                recorder?.stop()
            } catch (r: RuntimeException) {
                Log.d(TAG, "RuntimeException: stop() is called immediately after start()")
            } finally {
                releaseMediaRecorder()
            }
        }
    }

    fun releaseMediaRecorder() {
        recorder?.let { recorder ->
            // clear recorder configuration
            recorder.reset()
            // release the recorder object
            recorder.release()
        }
        // Lock camera for later use i.e taking it back from MediaRecorder.
        // MediaRecorder doesn't need it anymore and we will release it if the activity pauses.
        camera?.lock()
        camera = null
        recorder = null
    }

    private fun prepareRecord(camera: Camera, profile: CamcorderProfile, filePath: String): Boolean {
        try {
            recorder = MediaRecorder()
            recorder?.let { recorder ->
                camera.unlock()
                recorder.setCamera(camera)
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                recorder.setOrientationHint(90)
                recorder.setProfile(profile)
                recorder.setOutputFile(filePath)
                recorder.prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Exception prepareRecord: ")
            releaseMediaRecorder()
            return false
        }
        return true
    }
}