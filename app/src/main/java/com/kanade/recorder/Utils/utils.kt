package com.kanade.recorder.Utils

import android.content.ContentValues.TAG
import android.media.CamcorderProfile
import android.util.Log
import java.util.*

data class RecorderSize(var height: Int, var width: Int)

// 获取最优尺寸
fun getBestSize(list: List<RecorderSize>, th: Int, rate: Float): RecorderSize {
    Collections.sort(list, CameraSizeComparator())
    var i = 0
    for (s in list) {
        if (s.width > th && equalRate(s, rate)) {
            Log.i(TAG, "MakeSure Preview :w = " + s.width + " h = " + s.height)
            break
        }
        i++
    }
    if (i == list.size) {
        return getBestSize(list, rate)
    } else {
        return list[i]
    }
}

fun initProfile(width: Int, height: Int): CamcorderProfile {
    val profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
    // 比特率越高越清晰（前提是分辨率保持不变），分辨率越大视频尺寸越大。
    profile.videoFrameWidth = width
    profile.videoFrameHeight = height
    profile.videoBitRate = width * height * 2
    profile.audioBitRate = 96000
    return profile
}

private fun getBestSize(list: List<RecorderSize>, rate: Float): RecorderSize {
    var previewDisparity = 100f
    var index = 0
    for (i in list.indices) {
        val cur = list[i]
        val prop = cur.width.toFloat() / cur.height.toFloat()
        if (Math.abs(rate - prop) < previewDisparity) {
            previewDisparity = Math.abs(rate - prop)
            index = i
        }
    }
    return list[index]
}

private fun equalRate(s: RecorderSize, rate: Float): Boolean {
    val r = s.width.toFloat() / s.height.toFloat()
    return Math.abs(r - rate) <= 0.2
}

private class CameraSizeComparator : Comparator<RecorderSize> {
    override fun compare(lhs: RecorderSize, rhs: RecorderSize): Int {
        if (lhs.width == rhs.width) {
            return 0
        } else if (lhs.width > rhs.width) {
            return 1
        } else {
            return -1
        }
    }
}