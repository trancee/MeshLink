package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.Context
import android.os.Build
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.shadows.ShadowBluetoothGattServer
import org.robolectric.shadows.ShadowBluetoothManager
import org.robolectric.shadows.ShadowBluetoothServerSocket
import org.robolectric.shadows.ShadowBluetoothSocket
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

// Robolectric's own Bluetooth shadows leave out the calls below or answer them with fixed
// results; these record the radio's requests and script the stack's answers. Their state is static,
// so every test resets it with resetRadioShadows and registers the shadows it drives.

/** Clears every radio shadow's recorded calls and scripted answers. */
fun resetRadioShadows() {
    ShadowMeshAdapter.clear()
    ShadowMeshAdvertiser.clear()
    ShadowMeshManager.clear()
    ShadowMeshGattServer.clear()
    ShadowMeshDevice.clear()
    ShadowMeshGatt.clear()
    ShadowMeshServerSocket.clear()
    ShadowMeshSocket.clear()
}

/**
 * Withholds the advertiser while Bluetooth is on, as a stack without LE advertising may; the
 * platform getter recreates a cleared advertiser, so only a shadow can.
 */
@Implements(BluetoothAdapter::class)
class ShadowMeshAdapter : ShadowBluetoothAdapter() {
    @RealObject private lateinit var adapter: BluetoothAdapter

    @Implementation
    fun getBluetoothLeAdvertiser(): BluetoothLeAdvertiser? =
        if (withoutAdvertiser) null else reflector(AdapterReflector::class.java, adapter).getBluetoothLeAdvertiser()

    @Implementation(minSdk = Build.VERSION_CODES.Q)
    override fun listenUsingInsecureL2capChannel(): BluetoothServerSocket {
        listens.incrementAndGet()
        if (refuseListen) throw IOException("no L2CAP server")
        return super.listenUsingInsecureL2capChannel()
    }

    @ForType(BluetoothAdapter::class)
    private interface AdapterReflector {
        @Direct
        fun getBluetoothLeAdvertiser(): BluetoothLeAdvertiser?
    }

    companion object {
        var withoutAdvertiser = false
        var refuseListen = false

        /** How often the radio asked for an insecure L2CAP CoC server. */
        val listens = AtomicInteger()

        fun clear() {
            withoutAdvertiser = false
            refuseListen = false
            listens.set(0)
        }
    }
}

/** Records advertising sets instead of starting them. */
@Implements(BluetoothLeAdvertiser::class)
class ShadowMeshAdvertiser {
    /** An advertising set the radio asked for. */
    class Started(
        val parameters: AdvertisingSetParameters,
        val data: AdvertiseData,
        val scanResponse: AdvertiseData?,
        val callback: AdvertisingSetCallback,
    )

    @Implementation
    @Suppress("UnusedParameter") // The shadowed signature fixes the parameters.
    fun startAdvertisingSet(
        parameters: AdvertisingSetParameters,
        advertiseData: AdvertiseData,
        scanResponse: AdvertiseData?,
        periodicParameters: PeriodicAdvertisingParameters?,
        periodicData: AdvertiseData?,
        callback: AdvertisingSetCallback,
    ) {
        started += Started(parameters, advertiseData, scanResponse, callback)
    }

    @Implementation
    fun stopAdvertisingSet(callback: AdvertisingSetCallback) {
        stopped += callback
    }

    companion object {
        val started = mutableListOf<Started>()
        val stopped = mutableListOf<AdvertisingSetCallback>()

        fun clear() {
            started.clear()
            stopped.clear()
        }
    }
}

/** Fails the first [openFailures] `openGattServer` calls, as a stack that is still starting does. */
@Implements(android.bluetooth.BluetoothManager::class)
class ShadowMeshManager : ShadowBluetoothManager() {
    @Implementation(maxSdk = Build.VERSION_CODES.R)
    override fun openGattServer(
        context: Context,
        callback: BluetoothGattServerCallback,
        transport: Int,
    ): BluetoothGattServer? = if (refuse()) null else record(super.openGattServer(context, callback, transport))

    @Implementation(minSdk = Build.VERSION_CODES.S)
    override fun openGattServer(
        context: Context,
        callback: BluetoothGattServerCallback,
        transport: Int,
        eattSupport: Boolean,
    ): BluetoothGattServer? {
        val server = if (refuse()) null else super.openGattServer(context, callback, transport, eattSupport)
        return server?.let(::record)
    }

    private fun record(server: BluetoothGattServer): BluetoothGattServer = server.also { servers += it }

    private fun refuse(): Boolean {
        opens++
        return opens <= openFailures
    }

    companion object {
        var openFailures = 0
        var opens = 0
        val servers = mutableListOf<BluetoothGattServer>()

        fun clear() {
            openFailures = 0
            opens = 0
            servers.clear()
        }
    }
}

/** Records notifications and answers them with scripted results and `onNotificationSent` statuses. */
@Implements(BluetoothGattServer::class)
class ShadowMeshGattServer : ShadowBluetoothGattServer() {
    /** Every response this server sent. */
    val sent = mutableListOf<Response>()

    @Implementation
    override fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ): Boolean {
        sent += Response(device, requestId, status, offset, value)
        return super.sendResponse(device, requestId, status, offset, value)
    }

    @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
    fun notifyCharacteristicChanged(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
        value: ByteArray,
    ): Int = notify(device, characteristic, confirm, value)

    @Implementation
    @Suppress("DEPRECATION") // SDK 26-32 notify the value held in the characteristic.
    fun notifyCharacteristicChanged(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
    ): Boolean = notify(device, characteristic, confirm, characteristic.value) == GattExchange.STARTED

    private fun notify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
        value: ByteArray,
    ): Int {
        val result = notifyResults.removeFirstOrNull() ?: GattExchange.STARTED
        if (result == GattExchange.STARTED) {
            notified += Notified(device, characteristic, confirm, value.copyOf())
            sentStatuses.removeFirstOrNull()?.let { gattServerCallback.onNotificationSent(device, it) }
        }
        return result
    }

    /** A response to a central's request. */
    class Response(
        val device: BluetoothDevice,
        val requestId: Int,
        val status: Int,
        val offset: Int,
        val value: ByteArray?,
    )

    /** A notification the radio sent. */
    class Notified(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        val confirm: Boolean,
        val value: ByteArray,
    )

    companion object {
        val notified = mutableListOf<Notified>()

        /** The results of the next notify calls; [GattExchange.STARTED] once used up. */
        val notifyResults = ArrayDeque<Int>()

        /** The `onNotificationSent` status for each started notification; none once used up. */
        val sentStatuses = ArrayDeque<Int>()

        fun clear() {
            notified.clear()
            notifyResults.clear()
            sentStatuses.clear()
        }
    }
}

/** Creates GATT clients for both `connectGatt` overloads the radio uses, recording their arguments. */
@Implements(BluetoothDevice::class)
class ShadowMeshDevice : ShadowBluetoothDevice() {
    @RealObject private lateinit var device: BluetoothDevice

    @Implementation(minSdk = Build.VERSION_CODES.CINNAMON_BUN)
    fun connectGatt(
        settings: BluetoothGattConnectionSettings,
        executor: Executor,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? {
        connections += Connection(settings = settings, executor = executor)
        return open(callback)
    }

    @Implementation
    override fun connectGatt(
        context: Context,
        autoConnect: Boolean,
        callback: BluetoothGattCallback,
        transport: Int,
        phy: Int,
    ): BluetoothGatt? {
        connections += Connection(autoConnect = autoConnect, transport = transport, phy = phy)
        return open(callback)
    }

    @Implementation(minSdk = Build.VERSION_CODES.Q)
    override fun createInsecureL2capChannel(psm: Int): BluetoothSocket {
        if (refuseChannel) throw IOException("no L2CAP socket")
        // The real method needs a Bluetooth service; this is the factory the shadow server uses.
        val socket: BluetoothSocket =
            ReflectionHelpers.callStaticMethod(
                ShadowBluetoothSocket::class.java,
                "create",
                ReflectionHelpers.ClassParameter.from(BluetoothDevice::class.java, device),
            )
        channels += Channel(psm, socket)
        onChannel()
        return socket
    }

    private fun open(callback: BluetoothGattCallback): BluetoothGatt? {
        if (refuseConnect) return null
        val gatt = ShadowBluetoothGatt.newInstance(device)
        gatts += gatt
        val shadow = Shadow.extract<ShadowMeshGatt>(gatt)
        shadow.setGattCallback(callback)
        discoverable.forEach(shadow::addDiscoverableService)
        val status = connectStatus
        when {
            status != null -> callback.onConnectionStateChange(gatt, status, BluetoothGatt.STATE_DISCONNECTED)
            connectOnRequest -> shadow.notifyConnection(device.address)
        }
        return gatt
    }

    /** One `connectGatt` call: API 37's [settings] and [executor], or the legacy arguments. */
    class Connection(
        val settings: BluetoothGattConnectionSettings? = null,
        val executor: Executor? = null,
        val autoConnect: Boolean? = null,
        val transport: Int? = null,
        val phy: Int? = null,
    )

    /** One `createInsecureL2capChannel` call: the [psm] asked for and the [socket] created. */
    class Channel(
        val psm: Int,
        val socket: BluetoothSocket,
    ) {
        val shadow: ShadowMeshSocket get() = Shadow.extract(socket)
    }

    companion object {
        val connections = mutableListOf<Connection>()
        val channels = CopyOnWriteArrayList<Channel>()
        var refuseChannel = false

        /** Runs as each channel socket is created, before the radio gets it. */
        var onChannel: () -> Unit = {}
        var refuseConnect = false
        var connectOnRequest = true

        /** A status the connection fails with before `connectGatt` returns, as a racing callback can. */
        var connectStatus: Int? = null
        val discoverable = mutableListOf<BluetoothGattService>()

        /** Every GATT client created, in order. */
        val gatts = mutableListOf<BluetoothGatt>()

        fun clear() {
            connections.clear()
            refuseConnect = false
            connectOnRequest = true
            connectStatus = null
            discoverable.clear()
            gatts.clear()
            channels.clear()
            refuseChannel = false
            onChannel = {}
        }
    }
}

/** Scripts a GATT client's MTU, discovery, read and write answers and records its writes. */
@Implements(BluetoothGatt::class)
class ShadowMeshGatt : ShadowBluetoothGatt() {
    @RealObject private lateinit var gatt: BluetoothGatt

    @Implementation
    override fun requestMtu(mtu: Int): Boolean {
        requestedMtus += mtu
        if (refuseMtu) return false
        if (answerMtu) gattCallback.onMtuChanged(gatt, grantedMtu ?: mtu, mtuStatus)
        return true
    }

    @Implementation
    override fun discoverServices(): Boolean =
        when {
            !answerDiscovery -> true
            discoverStatus == BluetoothGatt.GATT_SUCCESS -> super.discoverServices()
            else -> true.also { gattCallback.onServicesDiscovered(gatt, discoverStatus) }
        }

    @Implementation
    @Suppress("DEPRECATION") // SDK 26-32 deliver a read only through the characteristic's value.
    override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        val value = record
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattCallback.onCharacteristicRead(gatt, characteristic, value, readStatus)
        } else {
            characteristic.value = value
            gattCallback.onCharacteristicRead(gatt, characteristic, readStatus)
        }
        return true
    }

    @Implementation
    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean = notificationsAllowed

    @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
    override fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int = describe(descriptor, value)

    @Implementation
    @Suppress("DEPRECATION") // SDK 26-32 write the value held in the descriptor.
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean =
        describe(descriptor, descriptor.value) == GattExchange.STARTED

    private fun describe(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Int {
        descriptorWrites += value.copyOf()
        gattCallback.onDescriptorWrite(gatt, descriptor, descriptorStatus)
        return GattExchange.STARTED
    }

    @Implementation(minSdk = Build.VERSION_CODES.TIRAMISU)
    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Int {
        writeTypes += writeType
        return write(characteristic, value)
    }

    @Implementation
    @Suppress("DEPRECATION") // SDK 26-32 write the value held in the characteristic.
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        writeTypes += characteristic.writeType
        return write(characteristic, characteristic.value) == GattExchange.STARTED
    }

    private fun write(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int {
        val result = writeResults.removeFirstOrNull() ?: GattExchange.STARTED
        if (result == GattExchange.STARTED) {
            written += value.copyOf()
            gattCallback.onCharacteristicWrite(gatt, characteristic, writeStatuses.removeFirstOrNull() ?: 0)
        }
        return result
    }

    companion object {
        var grantedMtu: Int? = null
        var mtuStatus = BluetoothGatt.GATT_SUCCESS
        var answerMtu = true
        var refuseMtu = false
        val requestedMtus = mutableListOf<Int>()
        var discoverStatus = BluetoothGatt.GATT_SUCCESS
        var answerDiscovery = true
        var record = ByteArray(0)
        var readStatus = BluetoothGatt.GATT_SUCCESS
        var notificationsAllowed = true
        var descriptorStatus = BluetoothGatt.GATT_SUCCESS
        val descriptorWrites = mutableListOf<ByteArray>()
        val written = mutableListOf<ByteArray>()
        val writeTypes = mutableListOf<Int>()

        /** The results of the next write calls; [GattExchange.STARTED] once used up. */
        val writeResults = ArrayDeque<Int>()

        /** The `onCharacteristicWrite` status for each started write; success once used up. */
        val writeStatuses = ArrayDeque<Int>()

        fun clear() {
            grantedMtu = null
            mtuStatus = BluetoothGatt.GATT_SUCCESS
            answerMtu = true
            refuseMtu = false
            requestedMtus.clear()
            discoverStatus = BluetoothGatt.GATT_SUCCESS
            answerDiscovery = true
            record = ByteArray(0)
            readStatus = BluetoothGatt.GATT_SUCCESS
            notificationsAllowed = true
            descriptorStatus = BluetoothGatt.GATT_SUCCESS
            descriptorWrites.clear()
            written.clear()
            writeTypes.clear()
            writeResults.clear()
            writeStatuses.clear()
        }
    }
}

/**
 * Lets a closed socket end its reads with EOF, as closing a platform socket aborts them, and lets a
 * test replace its input stream or fail its writes.
 */
@Implements(BluetoothSocket::class)
class ShadowMeshSocket : ShadowBluetoothSocket() {
    /** Replaces the socket's input stream when set before the first read. */
    @Volatile var input: InputStream? = null

    /** How often the socket was closed. */
    val closes = AtomicInteger()

    @Implementation
    public override fun getInputStream(): InputStream = input ?: super.getInputStream()

    @Implementation
    public override fun connect() {
        if (refuseConnect) throw IOException("connection refused")
        if (blockConnect) blockConnect()
        super.connect()
    }

    @Implementation
    public override fun close() {
        closes.incrementAndGet()
        super.close()
        inputStreamFeeder.close()
    }

    companion object {
        /** Fails every `connect`, as a peer that refuses the PSM does. */
        @Volatile var refuseConnect = false

        /** Holds every `connect` until its socket closes. */
        @Volatile var blockConnect = false

        fun clear() {
            refuseConnect = false
            blockConnect = false
        }
    }
}

/**
 * Assigns each server a distinct PSM, aborts `accept` when the server closes, as the platform does,
 * and lets a test fail a waiting `accept`.
 */
@Implements(BluetoothServerSocket::class)
class ShadowMeshServerSocket : ShadowBluetoothServerSocket() {
    private val psm = nextPsm.getAndIncrement()

    @Volatile private var closed = false

    @Volatile private var failed = false

    @Volatile var closes = 0
        private set

    init {
        servers += this
    }

    @Implementation(minSdk = Build.VERSION_CODES.Q)
    fun getPsm(): Int = psm

    @Implementation
    public override fun accept(timeout: Int): BluetoothSocket {
        while (true) {
            if (closed || failed) throw IOException("accept ended")
            val socket = runCatching { super.accept(POLL_MILLIS) }.getOrNull()
            if (socket != null) return socket
        }
    }

    @Implementation
    public override fun close() {
        closes++
        closed = true
        super.close()
    }

    /** A central on [device] opens a channel; returns this end's accepted socket. */
    fun connect(device: BluetoothDevice): BluetoothSocket = deviceConnected(device)

    /** Fails the waiting `accept` as a stack fault would, without closing the server. */
    fun fail() {
        failed = true
    }

    companion object {
        const val FIRST_PSM = 0x0080
        private const val POLL_MILLIS = 10
        private val nextPsm = AtomicInteger(FIRST_PSM)

        /** Every server created, in order. */
        val servers = CopyOnWriteArrayList<ShadowMeshServerSocket>()

        /** Ends every waiting `accept` so no test leaves a thread behind. */
        fun clear() {
            servers.forEach { it.failed = true }
            servers.clear()
            nextPsm.set(FIRST_PSM)
        }
    }
}
