package ch.trancee.lab

import ch.trancee.meshlink.internal.LinkRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A lab profile name (ADR 0007 decision 5) and the ticket that adds its security profile, if any is missing. */
internal enum class LabProfile(
    val label: String,
    val missingUntilTicket: Int?,
) {
    UNSECURED("unsecured", null),
    CLASSICAL("classical", CLASSICAL_TICKET),
    POST_QUANTUM("post-quantum", POST_QUANTUM_TICKET),
}

/**
 * The launch arguments of one lab run, parsed from `key=value` tokens by [parse].
 *
 * @throws LabArgsException naming the first argument that is invalid.
 */
internal class LabArgs private constructor(
    values: Map<String, String>,
    val run: String,
) {
    val scenario: String =
        values["scenario"].let { name ->
            argument(name == DIRECT_MESSAGE) { "scenario must be $DIRECT_MESSAGE, not $name" }
            DIRECT_MESSAGE
        }

    /** The link role this node is restricted to, or `null` for either. */
    val role: LinkRole? =
        (values["role"] ?: "any").let { name ->
            argument(name in ROLES) { "role must be one of ${ROLES.keys}, not $name" }
            ROLES.getValue(name)
        }

    /** The lab alias of the other phone; recorded, never resolved. */
    val peer: String = values["peer"] ?: "unknown"

    val mesh: String = values["mesh"] ?: "meshlink-lab"

    val profile: LabProfile =
        (values["profile"] ?: LabProfile.UNSECURED.label).let { name ->
            val profile = LabProfile.entries.firstOrNull { it.label == name }
            argument(profile != null) { "profile must be one of ${LabProfile.entries.map { it.label }}, not $name" }
            argument(profile.missingUntilTicket == null) {
                "profile $name needs a security profile this build lacks (ticket ${profile.missingUntilTicket})"
            }
            profile
        }

    val duration: Duration =
        (values["duration"] ?: "$DEFAULT_DURATION_SECONDS").toIntOrNull().let { seconds ->
            argument(seconds != null && seconds in DURATION_SECONDS) { "duration must be seconds in $DURATION_SECONDS" }
            seconds.seconds
        }

    companion object {
        const val DIRECT_MESSAGE = "direct-message"
        private val KEYS = setOf("scenario", "role", "peer", "mesh", "profile", "run", "duration")
        private val RUN_ID = Regex("[A-Za-z0-9._-]{1,64}")
        private val ROLES = mapOf("any" to null, "central" to LinkRole.CENTRAL, "peripheral" to LinkRole.PERIPHERAL)
        private val DURATION_SECONDS = 5..3_600
        private const val DEFAULT_DURATION_SECONDS = 60

        /**
         * Parses the `key=value` [tokens]; tokens without `=` or starting with `-` (the executable,
         * platform flags) are ignored.
         *
         * @throws LabArgsException naming the first argument that is missing, unknown or invalid.
         */
        fun parse(tokens: List<String>): LabArgs {
            val values = pairs(tokens)
            argument(values.keys.all { it in KEYS }) { "unknown argument ${(values.keys - KEYS).sorted()}" }
            val run = runId(tokens)
            argument(run != null) { "run must match ${RUN_ID.pattern}" }
            return LabArgs(values, run)
        }

        /** The valid `run` argument of [tokens], or `null`: where a run that fails to parse records why. */
        fun runId(tokens: List<String>): String? = pairs(tokens)["run"]?.takeIf { RUN_ID.matches(it) }

        private fun pairs(tokens: List<String>): Map<String, String> =
            tokens
                .filter { '=' in it && !it.startsWith("-") }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}

/** A launch argument is missing, unknown or invalid. */
internal class LabArgsException(
    message: String,
) : IllegalArgumentException(message)

@OptIn(kotlin.contracts.ExperimentalContracts::class)
private inline fun argument(
    valid: Boolean,
    message: () -> String,
) {
    kotlin.contracts.contract { returns() implies valid }
    if (!valid) throw LabArgsException(message())
}

private const val CLASSICAL_TICKET = 24
private const val POST_QUANTUM_TICKET = 25
