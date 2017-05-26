package com.kanade.recorder.Utils

import android.content.ContentValues.TAG
import android.graphics.Rect
import android.hardware.Camera
import android.util.Log
import java.util.*
import java.util.concurrent.locks.Lock

fun <T> lock(lock: Lock, body: () -> T): T {
    lock.lock()
    try {
        return body()
    }
    finally {
        lock.unlock()
    }
}

/**
 * Convert touch position x:y to [Camera.Area] position -1000:-1000 to 1000:1000.
 * 注意这里长边是width
 */
fun calculateTapArea(x: Float, y: Float, svWidth: Int, svHeight: Int, coefficient: Float): Rect {
    val focusAreaSize = 300f
    val areaSize = java.lang.Float.valueOf(focusAreaSize * coefficient).toInt()

    val left = clamp((x / svWidth * 2000 - 1000 - areaSize / 2).toInt(), -1000, 1000)
    val right = clamp(left + areaSize, -1000, 1000)
    val top = clamp((y / svHeight * 2000 - 1000 - areaSize / 2).toInt(), -1000, 1000)
    val bottom = clamp(top + areaSize, -1000, 1000)

    return Rect(left, top, right, bottom)
}

private fun clamp(x: Int, min: Int, max: Int): Int {
    if (x > max) {
        return max
    }
    if (x < min) {
        return min
    }
    return x
}

// 获取最优录像尺寸
data class RecorderSize(var height: Int, var width: Int)

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