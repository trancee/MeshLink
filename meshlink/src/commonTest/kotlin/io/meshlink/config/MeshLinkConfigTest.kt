package io.meshlink.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshLinkConfigTest {

    @Test
    fun presetsReturnCorrectDefaultsWithOverrides() {
        val chat = MeshLinkConfig.chatOptimized()
        assertEquals(10_000, chat.maxMessageSize)
        assertEquals(524_288, chat.bufferCapacity)

        val file = MeshLinkConfig.fileTransferOptimized()
        assertEquals(100_000, file.maxMessageSize)
        assertEquals(2_097_152, file.bufferCapacity)

        val power = MeshLinkConfig.powerOptimized()
        assertEquals(10_000, power.maxMessageSize)
        assertEquals(262_144, power.bufferCapacity)

        // Individual override after preset
        val custom = MeshLinkConfig.chatOptimized { maxMessageSize = 50_000 }
        assertEquals(50_000, custom.maxMessageSize)
        assertEquals(524_288, custom.bufferCapacity)
    }

    @Test
    fun crossFieldValidationReturnsAllViolations() {
        val violations = MeshLinkConfig(
            mtu = 10,
            maxMessageSize = 2_000_000,
            bufferCapacity = 1_000_000,
        ).validate()

        assertTrue(violations.size >= 2, "Should report at least 2 violations, got: $violations")
        assertTrue(violations.any { "mtu" in it.lowercase() })
        assertTrue(violations.any { "buffer" in it.lowercase() || "maxmessagesize" in it.lowercase() })
    }

    // --- Batch 9 Cycle 1: Config validation for new fields ---

    @Test
    fun newConfigFieldsValidateNonNegativeAndPositive() {
        val violations = MeshLinkConfig(
            diagnosticBufferCapacity = -1,
            dedupCapacity = 0,
            rateLimitMaxSends = 2,
            rateLimitWindowMs = 0,
            circuitBreakerMaxFailures = 1,
            circuitBreakerCooldownMs = -5,
        ).validate()

        assertTrue(violations.any { "diagnosticBufferCapacity" in it }, "Should reject negative diagnosticBufferCapacity: $violations")
        assertTrue(violations.any { "dedupCapacity" in it }, "Should reject zero dedupCapacity: $violations")
        assertTrue(violations.any { "rateLimitWindowMs" in it }, "Should reject zero rateLimitWindowMs when rate limiting enabled: $violations")
        assertTrue(violations.any { "circuitBreakerCooldownMs" in it }, "Should reject negative circuitBreakerCooldownMs: $violations")
    }

    // --- Batch 10 Cycle 4: Preset override with new fields ---

    @Test
    fun presetOverridePreservesNewFields() {
        // Override rate limiting in chatOptimized preset
        val chat = MeshLinkConfig.chatOptimized {
            rateLimitMaxSends = 5
            circuitBreakerMaxFailures = 3
        }
        assertEquals(10_000, chat.maxMessageSize, "Preset default should be preserved")
        assertEquals(524_288, chat.bufferCapacity, "Preset default should be preserved")
        assertEquals(5, chat.rateLimitMaxSends, "Override should apply")
        assertEquals(3, chat.circuitBreakerMaxFailures, "Override should apply")
        assertEquals(60_000L, chat.rateLimitWindowMs, "Non-overridden new field should keep builder default")

        // Validate catches violation when override creates inconsistency
        val invalid = MeshLinkConfig.fileTransferOptimized {
            bufferCapacity = 100  // less than maxMessageSize (100_000)
        }
        val violations = invalid.validate()
        assertTrue(violations.isNotEmpty(), "Should detect bufferCapacity < maxMessageSize: $violations")
    }

    @Test
    fun allPresetsPassValidation() {
        val presets = listOf(
            "chatOptimized" to MeshLinkConfig.chatOptimized(),
            "fileTransferOptimized" to MeshLinkConfig.fileTransferOptimized(),
            "powerOptimized" to MeshLinkConfig.powerOptimized(),
        )
        for ((name, config) in presets) {
            val violations = config.validate()
            assertEquals(emptyList(), violations, "$name should have no violations")
            assertTrue(config.mtu <= config.maxMessageSize, "$name: mtu must be <= maxMessageSize")
            assertTrue(config.maxMessageSize <= config.bufferCapacity, "$name: maxMessageSize must be <= bufferCapacity")
            assertTrue(config.relayQueueCapacity > 0, "$name: relayQueueCapacity must be > 0")
        }
    }

    @Test
    fun presetOverridesRelayQueueCapacity() {
        val custom = MeshLinkConfig.chatOptimized { relayQueueCapacity = 50 }
        assertEquals(50, custom.relayQueueCapacity)
        assertEquals(10_000, custom.maxMessageSize, "Other fields preserved")
    }

    // --- Cross-field validation rules from design doc §14 ---

    @Test
    fun ackWindowMaxMustBeGreaterOrEqualToAckWindowMin() {
        val violations = MeshLinkConfig(ackWindowMax = 1, ackWindowMin = 4).validate()
        assertTrue(violations.any { "ackWindowMax" in it && "ackWindowMin" in it },
            "Should reject ackWindowMax < ackWindowMin: $violations")
    }

    @Test
    fun ackWindowValidWhenMaxEqualsMin() {
        val violations = MeshLinkConfig(ackWindowMax = 4, ackWindowMin = 4).validate()
        assertTrue(violations.none { "ackWindow" in it },
            "ackWindowMax == ackWindowMin should be valid: $violations")
    }

    @Test
    fun powerModeThresholdsMustBeStrictlyDescending() {
        val violations = MeshLinkConfig(powerModeThresholds = listOf(30, 80)).validate()
        assertTrue(violations.any { "powerModeThresholds" in it && "descending" in it },
            "Should reject non-descending thresholds: $violations")
    }

    @Test
    fun powerModeThresholdsEqualValuesAreInvalid() {
        val violations = MeshLinkConfig(powerModeThresholds = listOf(50, 50)).validate()
        assertTrue(violations.any { "powerModeThresholds" in it },
            "Should reject equal thresholds: $violations")
    }

    @Test
    fun l2capRetryAttemptsMustBeNonNegativeWhenEnabled() {
        val violations = MeshLinkConfig(l2capEnabled = true, l2capRetryAttempts = -1).validate()
        assertTrue(violations.any { "l2capRetryAttempts" in it },
            "Should reject negative l2capRetryAttempts when l2capEnabled: $violations")
    }

    @Test
    fun l2capRetryAttemptsNegativeAllowedWhenDisabled() {
        val violations = MeshLinkConfig(l2capEnabled = false, l2capRetryAttempts = -1).validate()
        assertTrue(violations.none { "l2capRetryAttempts" in it },
            "Should allow negative l2capRetryAttempts when l2capEnabled is false: $violations")
    }

    @Test
    fun chunkInactivityTimeoutMustBeLessThanBufferTtl() {
        val violations = MeshLinkConfig(
            chunkInactivityTimeoutMs = 300_000L,
            bufferTtlMs = 300_000L,
        ).validate()
        assertTrue(violations.any { "chunkInactivityTimeoutMs" in it && "bufferTtlMs" in it },
            "Should reject chunkInactivityTimeoutMs >= bufferTtlMs: $violations")
    }

    @Test
    fun chunkInactivityTimeoutExceedingBufferTtlIsInvalid() {
        val violations = MeshLinkConfig(
            chunkInactivityTimeoutMs = 600_000L,
            bufferTtlMs = 300_000L,
        ).validate()
        assertTrue(violations.any { "chunkInactivityTimeoutMs" in it },
            "Should reject chunkInactivityTimeoutMs > bufferTtlMs: $violations")
    }

    @Test
    fun multipleCrossFieldViolationsReturnedTogether() {
        val violations = MeshLinkConfig(
            ackWindowMax = 1,
            ackWindowMin = 8,
            powerModeThresholds = listOf(10, 90),
            l2capEnabled = true,
            l2capRetryAttempts = -2,
            chunkInactivityTimeoutMs = 500_000L,
            bufferTtlMs = 100_000L,
        ).validate()
        assertTrue(violations.size >= 4,
            "Should report at least 4 cross-field violations, got ${violations.size}: $violations")
        assertTrue(violations.any { "ackWindow" in it })
        assertTrue(violations.any { "powerModeThresholds" in it })
        assertTrue(violations.any { "l2capRetryAttempts" in it })
        assertTrue(violations.any { "chunkInactivityTimeoutMs" in it })
    }

    @Test
    fun validConfigReturnsEmptyViolations() {
        val violations = MeshLinkConfig().validate()
        assertEquals(emptyList(), violations, "Default config should have no violations")
    }

    @Test
    fun defaultNewFieldsAreValid() {
        val config = MeshLinkConfig()
        assertEquals(2, config.ackWindowMin)
        assertEquals(16, config.ackWindowMax)
        assertEquals(listOf(80, 30), config.powerModeThresholds)
        assertEquals(true, config.l2capEnabled)
        assertEquals(3, config.l2capRetryAttempts)
        assertEquals(30_000L, config.chunkInactivityTimeoutMs)
        assertEquals(300_000L, config.bufferTtlMs)
    }
}
