package io.deepseekharness.mobile.shizuku

import android.hardware.HardwareBuffer
import android.os.Parcel
import android.os.Parcelable

/** Owns the server-side clone until Binder has marshalled it. */
class VirtualScreenFrameHandle(val buffer: HardwareBuffer) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) {
        try { buffer.writeToParcel(dest, flags) } finally { buffer.close() }
    }
    override fun describeContents(): Int = buffer.describeContents()
    companion object CREATOR : Parcelable.Creator<VirtualScreenFrameHandle> {
        override fun createFromParcel(source: Parcel) = VirtualScreenFrameHandle(HardwareBuffer.CREATOR.createFromParcel(source))
        override fun newArray(size: Int) = arrayOfNulls<VirtualScreenFrameHandle>(size)
    }
}
