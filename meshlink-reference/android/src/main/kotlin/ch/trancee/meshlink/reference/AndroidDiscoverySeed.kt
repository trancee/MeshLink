package ch.trancee.meshlink.reference

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

private const val RETAINED_DISCOVERY_SEED_FILE = "automation-discovery-seed.txt"
private const val SHARED_PREFS_PREFIX = "meshlink-"
private const val SHARED_PREFS_IDENTITY_SUFFIX = ":x25519-public"
private const val RETAINED_DISCOVERY_SEED_WAIT_MILLIS = 5_000L
private const val RETAINED_DISCOVERY_SEED_POLL_MILLIS = 250L

internal fun launchRetainedDiscoverySeedProbe(
    context: Context,
    appId: String,
    emitAutomationLog: (String) -> Unit,
    scope: CoroutineScope,
): Unit {
    if (appId.isBlank() || appId == "unknown") return
    scope.launch {
        val seed = waitForRetainedDiscoverySeed(context, appId)
        if (seed == null) {
            emitAutomationLog(
                "REFERENCE_AUTOMATION retained.discovery-seed unavailable appId=$appId",
            )
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=retained.discoverySeed.unavailable appId=$appId",
            )
            return@launch
        }
        val peerId = deriveRetainedDiscoveryPeerId(context, appId) ?: seed
        writeRetainedDiscoverySeedArtifact(context, peerId)
        emitAutomationLog(
            "REFERENCE_AUTOMATION retained.discovery-seed appId=$appId peerId=$peerId source=shared_prefs",
        )
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=retained.discoverySeed appId=$appId peerId=$seed",
        )
    }
}

internal fun readRetainedDiscoverySeed(context: Context, appId: String): String? {
    val sharedPrefs = context.getSharedPreferences("$SHARED_PREFS_PREFIX$appId", Context.MODE_PRIVATE)
    val directSeed = sharedPrefs.getString("identity:$appId$SHARED_PREFS_IDENTITY_SUFFIX", null)?.trim().orEmpty()
    if (directSeed.isNotBlank()) {
        return directSeed
    }
    return sharedPrefs.all.entries.firstOrNull { entry ->
        entry.key.endsWith(SHARED_PREFS_IDENTITY_SUFFIX) &&
            entry.value is String &&
            (entry.value as String).isNotBlank()
    }?.value as? String
}

private fun deriveRetainedDiscoveryPeerId(context: Context, appId: String): String? {
    val sharedPrefs = context.getSharedPreferences("$SHARED_PREFS_PREFIX$appId", Context.MODE_PRIVATE)
    val ed25519Public =
        sharedPrefs.getString("identity:$appId:ed25519-public", null)?.trim().orEmpty()
    val x25519Public =
        sharedPrefs.getString("identity:$appId:x25519-public", null)?.trim().orEmpty()
    if (ed25519Public.isBlank() || x25519Public.isBlank()) {
        return null
    }
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val publicKeyHash =
            digest.digest(
                Base64.decode(ed25519Public, Base64.NO_WRAP) + Base64.decode(x25519Public, Base64.NO_WRAP)
            )
        publicKeyHash.copyOfRange(0, 20).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }.getOrNull()
}

private suspend fun waitForRetainedDiscoverySeed(context: Context, appId: String): String? {
    val deadline = System.currentTimeMillis() + RETAINED_DISCOVERY_SEED_WAIT_MILLIS
    while (System.currentTimeMillis() < deadline) {
        val seed = readRetainedDiscoverySeed(context, appId)
        if (!seed.isNullOrBlank()) {
            return seed
        }
        delay(RETAINED_DISCOVERY_SEED_POLL_MILLIS)
    }
    return readRetainedDiscoverySeed(context, appId)
}

private fun writeRetainedDiscoverySeedArtifact(context: Context, seed: String): Unit {
    context.openFileOutput(RETAINED_DISCOVERY_SEED_FILE, Context.MODE_PRIVATE).use { output ->
        output.write(seed.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
