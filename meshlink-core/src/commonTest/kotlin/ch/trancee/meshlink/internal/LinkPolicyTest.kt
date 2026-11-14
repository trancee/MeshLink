package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkPolicyTest {
    private val mesh = ByteArray(DIGEST) { 1 }
    private val self = ByteArray(DIGEST) { 2 }
    private val policy = LinkPolicy(mesh, self)

    @Test
    fun selectsTheHighestVersionBothSidesSpeak() {
        assertEquals(1, policy.selectVersion(CapabilityRecord(listOf(2, 1), emptyList())))
        assertNull(policy.selectVersion(CapabilityRecord(listOf(2), emptyList())))
        val wide = LinkPolicy(mesh, self, listOf(3, 2, 1))
        assertEquals(2, wide.selectVersion(CapabilityRecord(listOf(4, 2, 1), emptyList())))
    }

    @Test
    fun helloCarriesTheVersionMeshAndNodeId() {
        val hello = policy.hello(1)

        assertEquals(1, hello.version)
        assertContentEquals(mesh, hello.meshIdDigest)
        assertContentEquals(self, hello.nodeId)
    }

    @Test
    fun peripheralChecksTheVersionThenTheMesh() {
        val peer = ByteArray(DIGEST) { 3 }
        val otherMesh = ByteArray(DIGEST) { 4 }

        assertNull(policy.checkHello(LinkFrame.PlainHello(1, mesh, peer), selected = null))
        assertEquals(
            CloseReason.UNSUPPORTED_VERSION,
            policy.checkHello(LinkFrame.PlainHello(2, otherMesh, peer), selected = null),
        )
        val wrongMesh = LinkFrame.PlainHello(1, otherMesh, peer)
        assertEquals(CloseReason.WRONG_MESH, policy.checkHello(wrongMesh, selected = null))
    }

    @Test
    fun centralExpectsTheVersionItSelected() {
        val peer = ByteArray(DIGEST) { 3 }
        val wide = LinkPolicy(mesh, self, listOf(2, 1))

        assertNull(wide.checkHello(LinkFrame.PlainHello(2, mesh, peer), selected = 2))
        assertEquals(CloseReason.PROTOCOL_ERROR, wide.checkHello(LinkFrame.PlainHello(1, mesh, peer), selected = 2))
    }

    @Test
    fun aHelloClaimingTheNodesOwnIdIsAProtocolError() {
        val own = LinkFrame.PlainHello(1, mesh, self)
        assertEquals(CloseReason.PROTOCOL_ERROR, policy.checkHello(own, selected = null))
    }

    @Test
    fun nodeKeepsItsOwnCentralLinkOnlyToAPeerWithAHigherNodeId() {
        assertTrue(policy.keepsOwnCentral(ByteArray(DIGEST) { 3 }))
        assertFalse(policy.keepsOwnCentral(ByteArray(DIGEST) { 1 }))
    }

    @Test
    fun nodeIdsCompareAsUnsignedBytes() {
        val low = ByteArray(DIGEST) { 0x7F }
        val high = ByteArray(DIGEST) { 0x7F }.also { it[DIGEST - 1] = 0x80.toByte() }

        assertTrue(LinkPolicy.compareNodeIds(low, high) < 0)
        assertTrue(LinkPolicy.compareNodeIds(high, low) > 0)
        assertEquals(0, LinkPolicy.compareNodeIds(low, low.copyOf()))
    }

    private companion object {
        const val DIGEST = 32
    }
}
