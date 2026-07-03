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

/**
 * Must match [ch.trancee.meshlink.identity.LocalIdentity]'s
 * `ADVERTISEMENT_KEY_HASH_SIZE_BYTES` (12 bytes / 24 hex chars). The retained discovery seed
 * is consumed by test automation as the "target peer id" to match against BLE-advertised peer
 * ids, so it must use the exact same hash length as the real over-the-air identity — otherwise
 * a sender seeded with this value will never find a scan result whose `hintPeerId` equals it,
 * permanently failing discovery (see the "scan discovery target mismatch" log line in
 * BleTransportAdapterScanSupport.kt).
 */
private const val RETAINED_DISCOVERY_PEER_ID_BYTES = 12

/** Bounded retry budget for waiting on the identity to be written to shared_prefs by the
 * MeshLinkController bootstrap, which happens asynchronously and races with this probe. */
private const val RETAINED_DISCOVERY_SEED_MAX_ATTEMPTS = 20
private const val RETAINED_DISCOVERY_SEED_RETRY_DELAY_MS = 250L

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
        // The identity (ed25519/x25519 keys) is written to shared_prefs asynchronously by the
        // MeshLinkController bootstrap, which races with this probe. A single check right after
        // onCreate frequently loses that race (confirmed on real devices), leaving this artifact
        // stale or missing and forcing test automation to fall back to a value that cannot match
        // the real over-the-air peer id. Poll with a bounded budget instead of giving up after
        // one attempt.
        var seedSnapshot = inspectRetainedDiscoverySeed(context, appId)
        var attempt = 1
        while (seedSnapshot.seed == null && attempt < RETAINED_DISCOVERY_SEED_MAX_ATTEMPTS) {
            delay(RETAINED_DISCOVERY_SEED_RETRY_DELAY_MS)
            seedSnapshot = inspectRetainedDiscoverySeed(context, appId)
            attempt++
        }
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
                    append(" attempts=")
                    append(attempt)
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
            "REFERENCE_AUTOMATION retained.discovery-seed appId=$appId peerId=$peerId " +
                "source=shared_prefs attempts=$attempt",
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

private fun writeRetainedDiscoverySeedArtifact(context: Context, seed: String): Unit {
    context.openFileOutput(RETAINED_DISCOVERY_SEED_FILE, Context.MODE_PRIVATE).use { output ->
        output.write(seed.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
