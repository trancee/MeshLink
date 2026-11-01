package bakeoff

enum class Role { CENTRAL, PERIPHERAL, DUAL, HUB_CENTRAL, HOLD }

enum class Phy(val label: String) { DEFAULT("default"), LE_1M("1m"), LE_2M("2m") }

enum class Bearer(val label: String) { GATT("gatt"), L2CAP_INSECURE("l2cap_insecure"), L2CAP_SECURE("l2cap_secure") }

enum class Plan { CONNECT, SUITE }

/** Launch arguments as `key=value` tokens (Android intent extra `args` is comma-separated). */
class BakeoffConfig(
    val role: Role,
    val runId: String,
    val commit: String,
    val runTag: Int,
    val attempts: Int,
    val phy: Phy,
    val bearers: List<Bearer>,
    val plan: Plan,
    val throughputBytes: Int,
    val echoCount: Int,
    val repetitions: Int,
    val durationSeconds: Int,
    val scanTimeoutSeconds: Int,
    val secureL2capServer: Boolean,
    val roundSeconds: Int,
) {
    companion object {
        fun parse(tokens: List<String>): BakeoffConfig {
            val values = tokens.flatMap { it.split(',') }
                .mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                .toMap()
            val runId = values["run"] ?: "adhoc"
            return BakeoffConfig(
                role = Role.valueOf((values["role"] ?: "peripheral").uppercase().replace('-', '_')),
                runId = runId,
                commit = values["commit"] ?: "unknown",
                runTag = values["tag"]?.toInt() ?: runId.hashCode(),
                attempts = values["n"]?.toInt() ?: 50,
                phy = Phy.entries.first { it.label == (values["phy"] ?: "default") },
                bearers = (values["bearers"] ?: "gatt").split('+').map(::parseBearer),
                plan = Plan.valueOf((values["plan"] ?: "suite").uppercase()),
                throughputBytes = values["bytes"]?.toInt() ?: 65536,
                echoCount = values["echo"]?.toInt() ?: 20,
                repetitions = values["reps"]?.toInt() ?: 5,
                durationSeconds = values["duration"]?.toInt() ?: 600,
                scanTimeoutSeconds = values["scan_timeout"]?.toInt() ?: 20,
                secureL2capServer = values["secure_server"] == "1",
                roundSeconds = values["round"]?.toInt() ?: 20,
            )
        }
    }
}

private fun parseBearer(label: String): Bearer = when (label) {
    "gatt" -> Bearer.GATT
    "coc" -> Bearer.L2CAP_INSECURE
    "coc_secure" -> Bearer.L2CAP_SECURE
    else -> throw IllegalArgumentException("unknown bearer $label")
}

class DeviceInfo(val model: String, val os: String, val platform: String) {
    val encoded: String get() = "$model|$os|$platform"

    companion object {
        fun decode(text: String): DeviceInfo {
            val parts = text.split('|')
            return DeviceInfo(parts.getOrElse(0) { "?" }, parts.getOrElse(1) { "?" }, parts.getOrElse(2) { "?" })
        }
    }
}
