package com.fitbittrack.pulse.direct

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A device that may offer live heart rate. [sharing] = it is announcing the heart-rate service right now. */
data class HeartDevice(val address: String, val name: String, val sharing: Boolean)

sealed interface LiveState {
    data object Off : LiveState
    data class Searching(val devices: List<HeartDevice> = emptyList()) : LiveState
    data class Connecting(val name: String) : LiveState
    data class Live(val device: HeartDevice, val bpm: Int?, val onSkin: Boolean, val history: List<Int>, val at: Instant) : LiveState {
        val name: String get() = device.name
        /** The number for other screens: none when the band is off the skin or has gone quiet. */
        fun bpmAt(now: Instant): Int? = bpm?.takeIf { onSkin && Duration.between(at, now) <= QUIET }
    }
    data class Failed(val message: String) : LiveState

    companion object {
        val QUIET: Duration = Duration.ofSeconds(10)
    }
}

/**
 * Lane 1: live heart rate straight from the band, over the standard Bluetooth Heart Rate Profile. The user
 * starts it on the band's side with "Share heart rate" in Google Health. Pulse only listens to that one
 * service; it never reads or changes anything else on the band. Nothing is stored.
 *
 * Everything runs on the main thread. The screen asks for the Bluetooth permission before calling [search].
 */
@SuppressLint("MissingPermission") // Checked by the screen; a late refusal is caught in guarded().
class LiveHeart(private val context: Context) {

    private val _state = MutableStateFlow<LiveState>(LiveState.Off)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /** Lists devices already connected to or paired with this phone, and any device sharing heart rate nearby. */
    fun search() = guarded {
        reset()
        val adapter = bluetooth?.adapter
        if (adapter == null) return@guarded fail("This phone has no Bluetooth.")
        if (!adapter.isEnabled) return@guarded fail("Bluetooth is off. Turn it on and try again.")
        _state.value = LiveState.Searching()
        // The band may already be connected for Google Health; then it doesn't announce itself any more.
        val known = bluetooth.getConnectedDevices(BluetoothProfile.GATT) +
            adapter.bondedDevices.orEmpty().filter { it.type == BluetoothDevice.DEVICE_TYPE_LE }
        known.forEach { add(it, sharing = false) }
        val scanner = adapter.bluetoothLeScanner ?: return@guarded
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scan,
        )
        scanning = true
        main.postDelayed(stopScanLater, SCAN_MS)
    }

    fun connect(device: HeartDevice) = guarded {
        stopScan()
        closeGatt()
        _state.value = LiveState.Connecting(device.name)
        val remote = bluetooth?.adapter?.getRemoteDevice(device.address) ?: return@guarded fail("Bluetooth is off.")
        gatt = remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main)
        main.postDelayed(tooSlow, CONNECT_MS)
    }

    fun stop() {
        guarded { reset() }
        _state.value = LiveState.Off
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = guarded { add(result.device, sharing = true) }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            if ((_state.value as? LiveState.Searching)?.devices?.isEmpty() == true) fail("Bluetooth search did not start ($errorCode). Try again in a minute.")
        }
    }

    private fun add(d: BluetoothDevice, sharing: Boolean) = _state.update { s ->
        if (s !is LiveState.Searching) return@update s
        val old = s.devices.firstOrNull { it.address == d.address }
        val new = HeartDevice(d.address, d.name ?: old?.name ?: "Unnamed device", sharing || old?.sharing == true)
        if (old == new) s
        else LiveState.Searching((s.devices.filter { it.address != d.address } + new).sortedWith(compareBy({ !it.sharing }, { it.name })))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = guarded {
            if (g !== gatt) return@guarded
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!g.discoverServices()) fail("The device did not answer.")
                BluetoothProfile.STATE_DISCONNECTED ->
                    fail(if (_state.value is LiveState.Live) "The connection was lost. Move closer and try again." else "Could not connect ($status). Start \"Share heart rate\" in Google Health and try again.")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = guarded {
            if (g !== gatt) return@guarded
            val c = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT)
            val d = c?.getDescriptor(CONFIG)
            if (c == null || d == null) return@guarded fail("This device is not sharing its heart rate. Start \"Share heart rate\" in Google Health, then try again.")
            g.setCharacteristicNotification(c, true)
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            if (!started) fail("The device did not accept the request.")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = guarded {
            if (g === gatt && status != BluetoothGatt.GATT_SUCCESS) {
                fail("The band refused ($status). It may only share with approved apps and machines.")
            }
        }

        @Deprecated("Used by Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { onValue(g, it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = onValue(g, value)
    }

    private fun onValue(g: BluetoothGatt, value: ByteArray) = guarded {
        if (g !== gatt) return@guarded
        main.removeCallbacks(tooSlow)
        val bpm = HeartRateProfile.bpm(value)
        val device = HeartDevice(g.device.address, g.device.name ?: "Band", sharing = true)
        val before = (_state.value as? LiveState.Live)?.history.orEmpty()
        _state.value = LiveState.Live(device, bpm, HeartRateProfile.onSkin(value), (before + listOfNotNull(bpm)).takeLast(HISTORY), Instant.now())
    }

    private val tooSlow = Runnable { fail("No heart rate arrived. Start \"Share heart rate\" in Google Health, then try again.") }
    private val stopScanLater = Runnable { guarded { stopScan() } }

    private fun fail(message: String) {
        guarded { reset() }
        _state.value = LiveState.Failed(message)
    }

    private fun reset() {
        stopScan()
        closeGatt()
    }

    private fun stopScan() {
        main.removeCallbacks(stopScanLater)
        if (!scanning) return
        scanning = false
        bluetooth?.adapter?.bluetoothLeScanner?.stopScan(scan)
    }

    private fun closeGatt() {
        main.removeCallbacks(tooSlow)
        val g = gatt ?: return
        gatt = null
        g.disconnect()
        g.close()
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            gatt = null
            scanning = false
            _state.value = LiveState.Failed("Pulse needs the Nearby devices permission.")
        }
    }

    private companion object {
        fun uuid16(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")
        val SERVICE: UUID = uuid16("180d")
        val MEASUREMENT: UUID = uuid16("2a37")
        val CONFIG: UUID = uuid16("2902")
        const val SCAN_MS = 30_000L
        const val CONNECT_MS = 25_000L
        const val HISTORY = 180
    }
}
