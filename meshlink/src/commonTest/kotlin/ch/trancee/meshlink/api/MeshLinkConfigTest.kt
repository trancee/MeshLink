package ch.trancee.meshlink.api

import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshLinkConfigTest {

    // ── Default construction ───────────────────────────────────────────────────

    @Test
    fun defaultConfigBuildsSuccessfully() {
        val config = meshLinkConfig("com.example.test")
        assertEquals("com.example.test", config.appId)
        assertTrue(config.clampWarnings.isEmpty())
    }

    @Test
    fun defaultMessagingValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(102_400, config.messaging.maxMessageSize)
        assertEquals(1_048_576, config.messaging.bufferCapacity)
        assertEquals(2, config.messaging.broadcastTtl)
        assertEquals(10_000, config.messaging.maxBroadcastSize)
    }

    @Test
    fun defaultTransportValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(517, config.transport.mtu)
        assertEquals(true, config.transport.l2capEnabled)
        assertEquals(false, config.transport.forceL2cap)
        assertEquals(false, config.transport.forceGatt)
        assertEquals(30_000L, config.transport.bootstrapDurationMillis)
        assertEquals(3, config.transport.l2capRetryAttempts)
    }

    @Test
    fun defaultSecurityValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(true, config.security.requireEncryption)
        assertEquals(TrustMode.STRICT, config.security.trustMode)
        assertEquals(30_000L, config.security.keyChangeTimeoutMillis)
        assertEquals(true, config.security.requireBroadcastSignatures)
        assertEquals(500L, config.security.ackJitterMaxMillis)
    }

    @Test
    fun defaultPowerValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(0.80f, config.power.powerModeThresholds.performanceThreshold)
        assertEquals(0.30f, config.power.powerModeThresholds.powerSaverThreshold)
        assertNull(config.power.customPowerMode)
        assertEquals(30_000L, config.power.evictionGracePeriodMillis)
    }

    @Test
    fun defaultRoutingValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(210_000L, config.routing.routeCacheTtlMillis)
        assertEquals(10, config.routing.maxHops)
        assertEquals(25_000, config.routing.dedupCapacity)
        assertEquals(2_700_000L, config.routing.maxMessageAgeMillis)
    }

    @Test
    fun defaultDiagnosticsValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(true, config.diagnostics.enabled)
        assertEquals(1_000, config.diagnostics.bufferCapacity)
        assertEquals(false, config.diagnostics.redactPeerIds)
    }

    @Test
    fun defaultRateLimitingValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(60, config.rateLimiting.maxSends)
        assertEquals(10, config.rateLimiting.broadcastLimit)
        assertEquals(1, config.rateLimiting.handshakeLimit)
    }

    @Test
    fun defaultTransferValues() {
        val config = meshLinkConfig("com.example.test")
        assertEquals(30_000L, config.transfer.chunkInactivityTimeout)
        assertEquals(4, config.transfer.maxConcurrentTransfers)
        assertEquals(1.0f, config.transfer.ackTimeoutMultiplier)
        assertEquals(0.5f, config.transfer.degradationThreshold)
    }

    // ── DSL builder ───────────────────────────────────────────────────────────

    @Test
    fun dslOverridesMessagingFields() {
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 50_000
                    bufferCapacity = 2_000_000
                    broadcastTtl = 3
                    maxBroadcastSize = 8_000
                }
            }
        assertEquals(50_000, config.messaging.maxMessageSize)
        assertEquals(2_000_000, config.messaging.bufferCapacity)
        assertEquals(3, config.messaging.broadcastTtl)
        assertEquals(8_000, config.messaging.maxBroadcastSize)
    }

    @Test
    fun dslOverridesTransportFields() {
        val config =
            meshLinkConfig("com.example.test") {
                transport {
                    mtu = 244
                    l2capEnabled = false
                    forceGatt = true
                    bootstrapDurationMillis = 10_000L
                    l2capRetryAttempts = 5
                }
            }
        assertEquals(244, config.transport.mtu)
        assertEquals(false, config.transport.l2capEnabled)
        assertEquals(true, config.transport.forceGatt)
        assertEquals(10_000L, config.transport.bootstrapDurationMillis)
        assertEquals(5, config.transport.l2capRetryAttempts)
    }

    @Test
    fun dslOverridesSecurityFields() {
        val config =
            meshLinkConfig("com.example.test") {
                security {
                    requireEncryption = false
                    trustMode = TrustMode.PROMPT
                    keyChangeTimeoutMillis = 60_000L
                    requireBroadcastSignatures = false
                    ackJitterMaxMillis = 200L
                }
            }
        assertEquals(false, config.security.requireEncryption)
        assertEquals(TrustMode.PROMPT, config.security.trustMode)
        assertEquals(60_000L, config.security.keyChangeTimeoutMillis)
        assertEquals(false, config.security.requireBroadcastSignatures)
        assertEquals(200L, config.security.ackJitterMaxMillis)
    }

    @Test
    fun dslOverridesPowerFields() {
        val config =
            meshLinkConfig("com.example.test") {
                power {
                    customPowerMode = PowerTier.BALANCED
                    evictionGracePeriodMillis = 60_000L
                    powerModeThresholds {
                        performanceThreshold = 0.90f
                        powerSaverThreshold = 0.20f
                    }
                }
            }
        assertEquals(PowerTier.BALANCED, config.power.customPowerMode)
        assertEquals(60_000L, config.power.evictionGracePeriodMillis)
        assertEquals(0.90f, config.power.powerModeThresholds.performanceThreshold)
        assertEquals(0.20f, config.power.powerModeThresholds.powerSaverThreshold)
    }

    @Test
    fun dslOverridesRoutingFields() {
        val config =
            meshLinkConfig("com.example.test") {
                routing {
                    routeCacheTtlMillis = 300_000L
                    maxHops = 5
                    dedupCapacity = 10_000
                    maxMessageAgeMillis = 1_800_000L
                }
            }
        assertEquals(300_000L, config.routing.routeCacheTtlMillis)
        assertEquals(5, config.routing.maxHops)
        assertEquals(10_000, config.routing.dedupCapacity)
        assertEquals(1_800_000L, config.routing.maxMessageAgeMillis)
    }

    @Test
    fun dslOverridesDiagnosticsFields() {
        val config =
            meshLinkConfig("com.example.test") {
                diagnostics {
                    enabled = false
                    bufferCapacity = 500
                    redactPeerIds = true
                }
            }
        assertEquals(false, config.diagnostics.enabled)
        assertEquals(500, config.diagnostics.bufferCapacity)
        assertEquals(true, config.diagnostics.redactPeerIds)
    }

    @Test
    fun dslOverridesRateLimitingFields() {
        val config =
            meshLinkConfig("com.example.test") {
                rateLimiting {
                    maxSends = 30
                    broadcastLimit = 5
                    handshakeLimit = 2
                }
            }
        assertEquals(30, config.rateLimiting.maxSends)
        assertEquals(5, config.rateLimiting.broadcastLimit)
        assertEquals(2, config.rateLimiting.handshakeLimit)
    }

    @Test
    fun dslOverridesTransferFields() {
        val config =
            meshLinkConfig("com.example.test") {
                transfer {
                    chunkInactivityTimeout = 60_000L
                    maxConcurrentTransfers = 8
                    ackTimeoutMultiplier = 2.0f
                    degradationThreshold = 0.3f
                }
            }
        assertEquals(60_000L, config.transfer.chunkInactivityTimeout)
        assertEquals(8, config.transfer.maxConcurrentTransfers)
        assertEquals(2.0f, config.transfer.ackTimeoutMultiplier)
        assertEquals(0.3f, config.transfer.degradationThreshold)
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    @Test
    fun smallPayloadLowLatencyPreset() {
        val config = MeshLinkConfig.smallPayloadLowLatency("com.example.test")
        assertEquals("com.example.test", config.appId)
        assertEquals(10_000, config.messaging.maxMessageSize)
        assertEquals(524_288, config.messaging.bufferCapacity)
        assertEquals(25_000, config.routing.dedupCapacity)
        assertTrue(config.clampWarnings.isEmpty())
    }

    @Test
    fun largePayloadHighThroughputPreset() {
        val config = MeshLinkConfig.largePayloadHighThroughput("com.example.test")
        assertEquals("com.example.test", config.appId)
        assertEquals(100_000, config.messaging.maxMessageSize)
        assertEquals(2_097_152, config.messaging.bufferCapacity)
        assertEquals(25_000, config.routing.dedupCapacity)
        assertTrue(config.transport.l2capEnabled)
        assertTrue(config.clampWarnings.isEmpty())
    }

    @Test
    fun minimalResourceUsagePreset() {
        val config = MeshLinkConfig.minimalResourceUsage("com.example.test")
        assertEquals("com.example.test", config.appId)
        assertEquals(10_000, config.messaging.maxMessageSize)
        assertEquals(262_144, config.messaging.bufferCapacity)
        assertEquals(10_000, config.routing.dedupCapacity)
        assertEquals(false, config.transport.l2capEnabled)
        assertTrue(config.clampWarnings.isEmpty())
    }

    @Test
    fun sensorTelemetryPreset() {
        val config = MeshLinkConfig.sensorTelemetry("com.example.test")
        assertEquals("com.example.test", config.appId)
        assertEquals(1_000, config.messaging.maxMessageSize)
        assertEquals(65_536, config.messaging.bufferCapacity)
        assertEquals(5_000, config.routing.dedupCapacity)
        assertEquals(false, config.transport.l2capEnabled)
        assertEquals(3, config.routing.maxHops)
        assertTrue(config.clampWarnings.isEmpty())
    }

    // ── Validation: safety-critical (throws) ──────────────────────────────────

    @Test
    fun throwsWhenMaxMessageSizeExceedsBufferCapacity() {
        assertFailsWith<IllegalArgumentException> {
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 200_000
                    bufferCapacity = 100_000
                }
            }
        }
    }

    @Test
    fun throwsWhenMaxMessageSizeEqualsBufferCapacityAfterClamp() {
        // bufferCapacity = 65_536 after clamp, maxMessageSize = 70_000 → should throw
        assertFailsWith<IllegalArgumentException> {
            meshLinkConfig("com.example.test") {
                messaging {
                    // bufferCapacity = 50_000 → clamped to 65_536
                    // maxMessageSize = 70_000 > 65_536 → throw
                    maxMessageSize = 70_000
                    bufferCapacity = 50_000
                }
            }
        }
    }

    @Test
    fun doesNotThrowWhenMaxMessageSizeEqualsBufferCapacity() {
        // Edge case: maxMessageSize == bufferCapacity should be allowed
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 100_000
                    bufferCapacity = 100_000
                }
            }
        assertEquals(100_000, config.messaging.maxMessageSize)
        assertEquals(100_000, config.messaging.bufferCapacity)
    }

    // ── Validation: best-effort clamps ────────────────────────────────────────

    @Test
    fun clampsBufferCapacityBelowMinimum() {
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 1_000
                    bufferCapacity = 10_000 // below 64 KB
                }
            }
        assertEquals(65_536, config.messaging.bufferCapacity)
        assertTrue(config.clampWarnings.any { it.startsWith("bufferCapacity:") })
    }

    @Test
    fun clampsBufferCapacityExactlyAt65536() {
        // 65_536 = 64 KB exactly — should NOT trigger a clamp
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 1_000
                    bufferCapacity = 65_536
                }
            }
        assertEquals(65_536, config.messaging.bufferCapacity)
        assertTrue(config.clampWarnings.none { it.startsWith("bufferCapacity:") })
    }

    @Test
    fun clampsMaxHopsBelowMinimum() {
        val config = meshLinkConfig("com.example.test") { routing { maxHops = 0 } }
        assertEquals(1, config.routing.maxHops)
        assertTrue(config.clampWarnings.any { it.startsWith("maxHops:") })
    }

    @Test
    fun clampsMaxHopsAboveMaximum() {
        val config = meshLinkConfig("com.example.test") { routing { maxHops = 25 } }
        assertEquals(20, config.routing.maxHops)
        assertTrue(config.clampWarnings.any { it.startsWith("maxHops:") })
    }

    @Test
    fun doesNotClampMaxHopsAtBoundary1() {
        val config = meshLinkConfig("com.example.test") { routing { maxHops = 1 } }
        assertEquals(1, config.routing.maxHops)
        assertTrue(config.clampWarnings.none { it.startsWith("maxHops:") })
    }

    @Test
    fun doesNotClampMaxHopsAtBoundary20() {
        val config = meshLinkConfig("com.example.test") { routing { maxHops = 20 } }
        assertEquals(20, config.routing.maxHops)
        assertTrue(config.clampWarnings.none { it.startsWith("maxHops:") })
    }

    @Test
    fun clampsBroadcastTtlToMaxHops() {
        val config =
            meshLinkConfig("com.example.test") {
                messaging { broadcastTtl = 15 }
                routing { maxHops = 10 }
            }
        assertEquals(10, config.messaging.broadcastTtl)
        assertTrue(config.clampWarnings.any { it.startsWith("broadcastTtl:") })
    }

    @Test
    fun doesNotClampBroadcastTtlWhenEqualToMaxHops() {
        val config =
            meshLinkConfig("com.example.test") {
                messaging { broadcastTtl = 10 }
                routing { maxHops = 10 }
            }
        assertEquals(10, config.messaging.broadcastTtl)
        assertTrue(config.clampWarnings.none { it.startsWith("broadcastTtl:") })
    }

    @Test
    fun clampsDedupCapacityBelowMinimum() {
        val config = meshLinkConfig("com.example.test") { routing { dedupCapacity = 500 } }
        assertEquals(1_000, config.routing.dedupCapacity)
        assertTrue(config.clampWarnings.any { it.startsWith("dedupCapacity:") })
    }

    @Test
    fun clampsDedupCapacityAboveMaximum() {
        val config = meshLinkConfig("com.example.test") { routing { dedupCapacity = 60_000 } }
        assertEquals(50_000, config.routing.dedupCapacity)
        assertTrue(config.clampWarnings.any { it.startsWith("dedupCapacity:") })
    }

    @Test
    fun doesNotClampDedupCapacityAtBoundaries() {
        val configMin = meshLinkConfig("com.example.test") { routing { dedupCapacity = 1_000 } }
        assertEquals(1_000, configMin.routing.dedupCapacity)
        assertTrue(configMin.clampWarnings.none { it.startsWith("dedupCapacity:") })

        val configMax = meshLinkConfig("com.example.test") { routing { dedupCapacity = 50_000 } }
        assertEquals(50_000, configMax.routing.dedupCapacity)
        assertTrue(configMax.clampWarnings.none { it.startsWith("dedupCapacity:") })
    }

    @Test
    fun multipleClampWarningsAccumulate() {
        // maxHops=0 → clamped, broadcastTtl=5 → clamped to 1, dedupCapacity=500 → clamped
        val config =
            meshLinkConfig("com.example.test") {
                messaging { broadcastTtl = 5 }
                routing {
                    maxHops = 0
                    dedupCapacity = 500
                }
            }
        assertEquals(3, config.clampWarnings.size)
    }

    @Test
    fun broadcastTtlClampedAfterMaxHopsClamp() {
        // maxHops=0 → clamped to 1; broadcastTtl=5 > 1 → clamped to 1
        val config =
            meshLinkConfig("com.example.test") {
                messaging { broadcastTtl = 5 }
                routing { maxHops = 0 }
            }
        assertEquals(1, config.routing.maxHops)
        assertEquals(1, config.messaging.broadcastTtl)
        // Two warnings: maxHops clamp + broadcastTtl clamp
        assertTrue(config.clampWarnings.any { it.startsWith("maxHops:") })
        assertTrue(config.clampWarnings.any { it.startsWith("broadcastTtl:") })
    }

    // ── Conversion methods ────────────────────────────────────────────────────

    @Test
    fun toMeshEngineConfigMapsRoutingFields() {
        val config =
            meshLinkConfig("com.example.test") {
                routing {
                    routeCacheTtlMillis = 300_000L
                    dedupCapacity = 15_000
                    maxMessageAgeMillis = 1_800_000L
                }
            }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(300_000L, engineConfig.routing.routeExpiryMillis)
        assertEquals(15_000, engineConfig.routing.dedupCapacity)
        assertEquals(1_800_000L, engineConfig.routing.dedupTtlMillis)
    }

    @Test
    fun toMeshEngineConfigMapsMessagingFields() {
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 50_000
                    bufferCapacity = 1_000_000
                    broadcastTtl = 3
                    maxBroadcastSize = 15_000
                }
                security { requireBroadcastSignatures = false }
                rateLimiting {
                    maxSends = 30
                    broadcastLimit = 5
                    handshakeLimit = 2
                }
            }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(50_000, engineConfig.messaging.maxMessageSize)
        assertEquals(3.toUByte(), engineConfig.messaging.broadcastTtl)
        assertEquals(15_000, engineConfig.messaging.maxBroadcastSize)
        assertEquals(false, engineConfig.messaging.requireBroadcastSignatures)
        assertEquals(30, engineConfig.messaging.outboundUnicastLimit)
        assertEquals(5, engineConfig.messaging.broadcastLimit)
        assertEquals(2, engineConfig.messaging.handshakeLimit)
    }

    @Test
    fun toMeshEngineConfigDerivesAppIdHash() {
        val config = meshLinkConfig("com.example.test")
        val engineConfig = config.toMeshEngineConfig()
        // appIdHash must be 2 bytes (FNV-1a XOR-fold → UShort → ByteArray)
        assertEquals(2, engineConfig.messaging.appIdHash.size)
    }

    @Test
    fun toMeshEngineConfigDifferentAppIdsProduceDifferentHashes() {
        val config1 = meshLinkConfig("com.example.app1").toMeshEngineConfig()
        val config2 = meshLinkConfig("com.example.app2").toMeshEngineConfig()
        // Different appIds should (with very high probability) yield different hashes
        val hash1 = config1.messaging.appIdHash
        val hash2 = config2.messaging.appIdHash
        val sameHash = hash1[0] == hash2[0] && hash1[1] == hash2[1]
        assertEquals(false, sameHash)
    }

    @Test
    fun toMeshEngineConfigMapsPowerFields() {
        val config =
            meshLinkConfig("com.example.test") {
                power {
                    powerModeThresholds {
                        performanceThreshold = 0.75f
                        powerSaverThreshold = 0.25f
                    }
                    evictionGracePeriodMillis = 60_000L
                }
                transport { bootstrapDurationMillis = 20_000L }
            }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(0.75f, engineConfig.power.performanceThreshold)
        assertEquals(0.25f, engineConfig.power.powerSaverThreshold)
        assertEquals(60_000L, engineConfig.power.evictionGracePeriodMillis)
        assertEquals(20_000L, engineConfig.power.bootstrapDurationMillis)
    }

    @Test
    fun toMeshEngineConfigMapsTransferInactivityTimeout() {
        val config =
            meshLinkConfig("com.example.test") { transfer { chunkInactivityTimeout = 60_000L } }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(60_000L, engineConfig.transfer.inactivityBaseTimeoutMillis)
    }

    @Test
    fun toMeshEngineConfigSelectsL2capChunkPolicy() {
        val configL2cap = meshLinkConfig("com.example.test") { transport { l2capEnabled = true } }
        assertEquals(ChunkSizePolicy.L2CAP.size, configL2cap.toMeshEngineConfig().chunkSize.size)
    }

    @Test
    fun toMeshEngineConfigSelectsGattChunkPolicy() {
        val configGatt = meshLinkConfig("com.example.test") { transport { l2capEnabled = false } }
        assertEquals(ChunkSizePolicy.GATT.size, configGatt.toMeshEngineConfig().chunkSize.size)
    }

    @Test
    fun toBleTransportConfigMapsAppIdAndForceFlags() {
        val config =
            meshLinkConfig("com.example.myapp") {
                transport {
                    forceL2cap = true
                    forceGatt = false
                }
            }
        val bleConfig = config.toBleTransportConfig()
        assertEquals("com.example.myapp", bleConfig.appId)
        assertEquals(true, bleConfig.forceL2cap)
        assertEquals(false, bleConfig.forceGatt)
    }

    @Test
    fun toBleTransportConfigDefaultsForceFlags() {
        val config = meshLinkConfig("com.example.test")
        val bleConfig = config.toBleTransportConfig()
        assertEquals(false, bleConfig.forceL2cap)
        assertEquals(false, bleConfig.forceGatt)
    }

    // ── Buffered message count derivation ─────────────────────────────────────

    @Test
    fun maxBufferedMessagesIsAtLeastOne() {
        // bufferCapacity = 65_536, maxMessageSize = 102_400 → ratio < 1 → clamped to 1
        // Actually maxMessageSize > bufferCapacity → should throw
        // Use a case where ratio rounds down: bufferCapacity = 65_536, maxMessageSize = 65_536
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 65_536
                    bufferCapacity = 65_536
                }
            }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(1, engineConfig.messaging.maxBufferedMessages)
    }

    @Test
    fun maxBufferedMessagesCalculatedCorrectly() {
        // bufferCapacity = 1_000_000, maxMessageSize = 100_000 → 10
        val config =
            meshLinkConfig("com.example.test") {
                messaging {
                    maxMessageSize = 100_000
                    bufferCapacity = 1_000_000
                }
            }
        val engineConfig = config.toMeshEngineConfig()
        assertEquals(10, engineConfig.messaging.maxBufferedMessages)
    }

    // ── TrustMode enum ────────────────────────────────────────────────────────

    @Test
    fun trustModeEnumValues() {
        val values = TrustMode.entries
        assertEquals(2, values.size)
        assertTrue(TrustMode.STRICT in values)
        assertTrue(TrustMode.PROMPT in values)
    }

    // ── Data class equality and copy ─────────────────────────────────────────

    @Test
    fun configEqualityAndCopy() {
        val config1 = meshLinkConfig("com.example.test")
        val config2 = meshLinkConfig("com.example.test")
        assertEquals(config1, config2)

        val modified = config1.copy(appId = "com.example.other")
        assertEquals("com.example.other", modified.appId)
        assertEquals(config1.messaging, modified.messaging)
    }

    @Test
    fun differentAppIdsAreNotEqual() {
        val config1 = meshLinkConfig("com.example.app1")
        val config2 = meshLinkConfig("com.example.app2")
        assertEquals(false, config1 == config2)
    }

    // ── Nested data class equality ────────────────────────────────────────────

    @Test
    fun messagingConfigEquality() {
        val m1 = MessagingConfig(maxMessageSize = 10_000)
        val m2 = MessagingConfig(maxMessageSize = 10_000)
        assertEquals(m1, m2)
    }

    @Test
    fun powerModeThresholdsEquality() {
        val t1 = PowerModeThresholds(performanceThreshold = 0.9f)
        val t2 = PowerModeThresholds(performanceThreshold = 0.9f)
        assertEquals(t1, t2)
    }

    // ── Preset appId forwarding ────────────────────────────────────────────────

    @Test
    fun allPresetsForwardAppId() {
        val appId = "com.example.test"
        assertEquals(appId, MeshLinkConfig.smallPayloadLowLatency(appId).appId)
        assertEquals(appId, MeshLinkConfig.largePayloadHighThroughput(appId).appId)
        assertEquals(appId, MeshLinkConfig.minimalResourceUsage(appId).appId)
        assertEquals(appId, MeshLinkConfig.sensorTelemetry(appId).appId)
    }

    // ── Engine config non-null assertions ─────────────────────────────────────

    @Test
    fun toMeshEngineConfigIsNotNull() {
        val engineConfig = meshLinkConfig("com.example.test").toMeshEngineConfig()
        assertNotNull(engineConfig)
        assertNotNull(engineConfig.routing)
        assertNotNull(engineConfig.messaging)
        assertNotNull(engineConfig.transfer)
        assertNotNull(engineConfig.power)
        assertNotNull(engineConfig.handshake)
        assertNotNull(engineConfig.chunkSize)
    }

    @Test
    fun toBleTransportConfigIsNotNull() {
        val bleConfig = meshLinkConfig("com.example.test").toBleTransportConfig()
        assertNotNull(bleConfig)
        assertNotNull(bleConfig.appId)
    }

    // ── PowerConfig with customPowerMode ──────────────────────────────────────

    @Test
    fun customPowerModePreservedInConfig() {
        val config =
            meshLinkConfig("com.example.test") { power { customPowerMode = PowerTier.POWER_SAVER } }
        assertEquals(PowerTier.POWER_SAVER, config.power.customPowerMode)
    }

    @Test
    fun nullCustomPowerModeIsDefault() {
        val config = meshLinkConfig("com.example.test")
        assertNull(config.power.customPowerMode)
    }

    // ── Direct builder build() coverage (internal paths) ─────────────────────

    @Test
    fun messagingConfigBuilderBuildDefaults() {
        val builder = MessagingConfigBuilder()
        val cfg = builder.build()
        assertEquals(102_400, cfg.maxMessageSize)
        assertEquals(1_048_576, cfg.bufferCapacity)
        assertEquals(2, cfg.broadcastTtl)
        assertEquals(10_000, cfg.maxBroadcastSize)
    }

    @Test
    fun messagingConfigBuilderBuildCustomValues() {
        val builder =
            MessagingConfigBuilder().apply {
                maxMessageSize = 512
                bufferCapacity = 131_072
                broadcastTtl = 3
                maxBroadcastSize = 5_000
            }
        val cfg = builder.build()
        assertEquals(512, cfg.maxMessageSize)
        assertEquals(131_072, cfg.bufferCapacity)
        assertEquals(3, cfg.broadcastTtl)
        assertEquals(5_000, cfg.maxBroadcastSize)
    }

    @Test
    fun routingConfigBuilderBuildDefaults() {
        val builder = RoutingConfigBuilder()
        val cfg = builder.build()
        assertEquals(210_000L, cfg.routeCacheTtlMillis)
        assertEquals(10, cfg.maxHops)
        assertEquals(25_000, cfg.dedupCapacity)
        assertEquals(2_700_000L, cfg.maxMessageAgeMillis)
    }

    @Test
    fun routingConfigBuilderBuildCustomValues() {
        val builder =
            RoutingConfigBuilder().apply {
                routeCacheTtlMillis = 60_000L
                maxHops = 5
                dedupCapacity = 10_000
                maxMessageAgeMillis = 900_000L
            }
        val cfg = builder.build()
        assertEquals(60_000L, cfg.routeCacheTtlMillis)
        assertEquals(5, cfg.maxHops)
        assertEquals(10_000, cfg.dedupCapacity)
        assertEquals(900_000L, cfg.maxMessageAgeMillis)
    }
}
