package com.alemnt.midipro

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import android.os.ParcelUuid
import com.alemnt.midi.MidiEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltAndroidApp
class MidiApplication : Application() {
    @Inject lateinit var midi: MidiEngine
}

@Module
@InstallIn(SingletonComponent::class)
object MidiModule {
    @Provides @Singleton fun engine(@ApplicationContext context: Context) = MidiEngine(context)
}

class VirtualMidiService : MidiDeviceService() {
    private val engine get() = (application as MidiApplication).midi

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = emptyArray()

    override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
        engine.virtualReceiver(outputPortReceivers.firstOrNull(), status.getOutputPortOpenCount(0))
    }

    override fun onDestroy() {
        engine.virtualReceiver(null, 0)
        super.onDestroy()
    }
}

data class BlePeripheral(val device: BluetoothDevice, val name: String, val address: String)

@Singleton
@SuppressLint("MissingPermission")
class BleDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val _peripherals = MutableStateFlow<List<BlePeripheral>>(emptyList())
    val peripherals = _peripherals.asStateFlow()
    private val _feedback = MutableStateFlow("Buscar periféricos BLE MIDI")
    val feedback = _feedback.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val item = BlePeripheral(device, result.scanRecord?.deviceName ?: device.name ?: "MIDI BLE", device.address)
            if (_peripherals.value.none { it.address == item.address }) _peripherals.value += item
        }

        override fun onScanFailed(errorCode: Int) {
            _feedback.value = "Error de escaneo ($errorCode)"
        }
    }

    fun start() {
        stop()
        try {
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                _feedback.value = "Activa Bluetooth para buscar"
                return
            }
            _peripherals.value = emptyList()
            val uuid = ParcelUuid(UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700"))
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(uuid).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            _feedback.value = "Buscando MIDI BLE…"
        } catch (_: SecurityException) {
            _feedback.value = "Permite dispositivos cercanos para buscar MIDI BLE"
        }
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        if (_feedback.value == "Buscando MIDI BLE…") _feedback.value = "Búsqueda finalizada"
    }
}
