package com.kanade.recorder

import android.os.Parcel
import android.os.Parcelable

data class RecorderResult(var filepath: String,
                          var duration: Int,
                          var width: Int,
                          var height: Int) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(filepath)
        dest.writeInt(duration)
        dest.writeInt(width)
        dest.writeInt(height)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<RecorderResult> {
            override fun createFromParcel(source: Parcel): RecorderResult =
                    RecorderResult(source.readString(),
                            source.readInt(),
                            source.readInt(),
                            source.readInt())

            override fun newArray(size: Int): Array<RecorderResult?> = arrayOfNulls(size)
        }
    }
}