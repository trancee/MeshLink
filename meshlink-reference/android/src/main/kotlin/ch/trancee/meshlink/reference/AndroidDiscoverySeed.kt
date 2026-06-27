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
private const val RETAINED_DISCOVERY_SEED_WAIT_MILLIS = 15_000L
private const val RETAINED_DISCOVERY_SEED_POLL_MILLIS = 250L
private const val RETAINED_DISCOVERY_SEED_PROGRESS_LOG_INTERVAL = 4
private const val RETAINED_DISCOVERY_PEER_ID_BYTES = 20

private data class RetainedDiscoverySeedSnapshot(
    val seed: String?,
    val sharedPrefKeyCount: Int,
    val hasDirectIdentitySeed: Boolean,
    val hasEd25519Public: Boolean,
    val hasX25519Public: Boolean,
)

internal fun launchRetainedDiscoverySeedProbe(
    context: Context,
    appId: String,
    emitAutomationLog: (String) -> Unit,
    scope: CoroutineScope,
): Unit {
    if (appId.isBlank() || appId == "unknown") return
    scope.launch {
        val seedSnapshot = waitForRetainedDiscoverySeed(context, appId, emitAutomationLog)
        if (seedSnapshot.seed == null) {
            emitAutomationLog(
                buildString {
                    append("REFERENCE_AUTOMATION retained.discovery-seed unavailable appId=")
                    append(appId)
                    append(" keys=")
                    append(seedSnapshot.sharedPrefKeyCount)
                    append(" directSeed=")
                    append(seedSnapshot.hasDirectIdentitySeed)
                    append(" ed25519Public=")
                    append(seedSnapshot.hasEd25519Public)
                    append(" x25519Public=")
                    append(seedSnapshot.hasX25519Public)
                },
            )
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=retained.discoverySeed.unavailable appId=$appId",
            )
            return@launch
        }
        val peerId = deriveRetainedDiscoveryPeerId(context, appId) ?: seedSnapshot.seed
        writeRetainedDiscoverySeedArtifact(context, peerId)
        emitAutomationLog(
            "REFERENCE_AUTOMATION retained.discovery-seed appId=$appId peerId=$peerId source=shared_prefs",
        )
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=retained.discoverySeed appId=$appId peerId=$peerId",
        )
    }
}

internal fun readRetainedDiscoverySeed(context: Context, appId: String): String? {
    return inspectRetainedDiscoverySeed(context, appId).seed
}

private fun inspectRetainedDiscoverySeed(context: Context, appId: String): RetainedDiscoverySeedSnapshot {
    val sharedPrefs = context.getSharedPreferences("$SHARED_PREFS_PREFIX$appId", Context.MODE_PRIVATE)
    val directSeed =
        sharedPrefs.getString("identity:$appId$SHARED_PREFS_IDENTITY_SUFFIX", null)?.trim().orEmpty()
    val firstIdentitySeed =
        sharedPrefs.all.entries.firstOrNull { entry ->
            entry.key.endsWith(SHARED_PREFS_IDENTITY_SUFFIX) &&
                entry.value is String &&
                (entry.value as String).isNotBlank()
        }?.value as? String
    return RetainedDiscoverySeedSnapshot(
        seed = if (directSeed.isNotBlank()) directSeed else firstIdentitySeed,
        sharedPrefKeyCount = sharedPrefs.all.keys.size,
        hasDirectIdentitySeed = directSeed.isNotBlank(),
        hasEd25519Public =
            sharedPrefs.getString("identity:$appId:ed25519-public", null)?.trim().orEmpty().isNotBlank(),
        hasX25519Public = directSeed.isNotBlank(),
    )
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
                Base64.decode(ed25519Public, Base64.NO_WRAP) +
                    Base64.decode(x25519Public, Base64.NO_WRAP)
            )
        publicKeyHash
            .copyOfRange(0, RETAINED_DISCOVERY_PEER_ID_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }.getOrNull()
}

private suspend fun waitForRetainedDiscoverySeed(
    context: Context,
    appId: String,
    emitAutomationLog: (String) -> Unit,
): RetainedDiscoverySeedSnapshot {
    val deadline = System.currentTimeMillis() + RETAINED_DISCOVERY_SEED_WAIT_MILLIS
    var attempt = 0
    while (System.currentTimeMillis() < deadline) {
        attempt += 1
        val snapshot = inspectRetainedDiscoverySeed(context, appId)
        if (!snapshot.seed.isNullOrBlank()) {
            return snapshot
        }
        if (
            attempt == 1 ||
                attempt % RETAINED_DISCOVERY_SEED_PROGRESS_LOG_INTERVAL == 0 ||
                System.currentTimeMillis() + RETAINED_DISCOVERY_SEED_POLL_MILLIS >= deadline
        ) {
            emitAutomationLog(
                buildString {
                    append("REFERENCE_AUTOMATION retained.discovery-seed pending appId=")
                    append(appId)
                    append(" attempt=")
                    append(attempt)
                    append(" keys=")
                    append(snapshot.sharedPrefKeyCount)
                    append(" directSeed=")
                    append(snapshot.hasDirectIdentitySeed)
                    append(" ed25519Public=")
                    append(snapshot.hasEd25519Public)
                    append(" x25519Public=")
                    append(snapshot.hasX25519Public)
                },
            )
        }
        delay(RETAINED_DISCOVERY_SEED_POLL_MILLIS)
    }
    return inspectRetainedDiscoverySeed(context, appId)
}

private fun writeRetainedDiscoverySeedArtifact(context: Context, seed: String): Unit {
    context.openFileOutput(RETAINED_DISCOVERY_SEED_FILE, Context.MODE_PRIVATE).use { output ->
        output.write(seed.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
