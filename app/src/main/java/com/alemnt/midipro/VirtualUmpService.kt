package com.alemnt.midipro

import android.annotation.TargetApi
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import android.media.midi.MidiUmpDeviceService
import com.alemnt.midi.Wire

@TargetApi(35)
class VirtualUmpService : MidiUmpDeviceService() {
    private val engine get() = (application as MidiApplication).midi
    private val unusedInput = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) = Unit
    }

    override fun onGetInputPortReceivers(): List<MidiReceiver> = listOf(unusedInput)

    override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
        engine.virtualReceiver(outputPortReceivers.firstOrNull(), status.getOutputPortOpenCount(0), Wire.UMP2)
    }

    override fun onDestroy() {
        engine.virtualReceiver(null, 0, Wire.UMP2)
        super.onDestroy()
    }
}
