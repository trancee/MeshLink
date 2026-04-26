package ch.trancee.meshlink.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.createCryptoProvider as buildCryptoProvider
import ch.trancee.meshlink.engine.MeshEngineConfig
import ch.trancee.meshlink.routing.RoutingConfig
import ch.trancee.meshlink.transport.BleTransportConfig
import ch.trancee.meshlink.transport.MeshLinkService

/**
 * Sample subclass of [MeshLinkService] wired for the S04 two-device integration test harness.
 *
 * - Uses [ch.trancee.meshlink.crypto.AndroidCryptoProvider] (via [buildCryptoProvider]).
 * - BLE appId = "ch.trancee.meshlink.sample"; GATT fallback controlled by [BuildConfig.FORCE_GATT].
 * - Routing timers shortened for faster UAT turn-around (Hello every 2 s, expiry at 60 s).
 * - Foreground notification posted on channel [CHANNEL_ID].
 */
class SampleMeshService : MeshLinkService() {

    companion object {
        const val CHANNEL_ID = "meshlink_sample_channel"
        private const val CHANNEL_NAME = "MeshLink Sample"
    }

    override fun createBleTransportConfig(): BleTransportConfig =
        BleTransportConfig(
            appId = "ch.trancee.meshlink.sample",
            forceGatt = BuildConfig.FORCE_GATT,
        )

    override fun createCryptoProvider(): CryptoProvider = buildCryptoProvider()

    override fun createMeshEngineConfig(): MeshEngineConfig =
        MeshEngineConfig(
            routing =
                RoutingConfig(
                    helloIntervalMillis = 2_000L,
                    routeExpiryMillis = 60_000L,
                )
        )

    override fun createForegroundNotification(): Notification {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshLink Sample")
            .setContentText("BLE mesh running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
