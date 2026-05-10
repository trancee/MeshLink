package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothManager
import android.content.Context
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class AndroidBleTransport(
    private val context: Context,
) : BleTransport {
    private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 16)
    private var started: Boolean = false

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        context.getSystemService(BluetoothManager::class.java)
        started = true
    }

    override suspend fun pause(): Unit {
        started = false
    }

    override suspend fun resume(): Unit {
        started = true
    }

    override suspend fun stop(): Unit {
        started = false
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return if (started) {
            TransportSendResult.Dropped("Android BLE direct transport is not connected")
        } else {
            TransportSendResult.Dropped("Android BLE direct transport is not started")
        }
    }
}
