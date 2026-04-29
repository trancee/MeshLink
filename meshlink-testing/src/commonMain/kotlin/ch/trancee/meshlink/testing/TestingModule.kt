@file:Suppress("unused")

package ch.trancee.meshlink.testing

/**
 * MeshLink Testing Module
 *
 * This module re-exports testing utilities from `ch.trancee.meshlink.testing`:
 *
 * - [VirtualMeshTransport] — A virtual BLE transport for integration tests.
 * - [linkedTransports] — Factory to create a pair of bidirectionally linked transports.
 * - [MeshLink.createForTest][createForTest] — Factory to create a MeshLink instance for tests.
 *
 * All types are provided by the `:meshlink` module and transitively available through this module's
 * `api(project(":meshlink"))` dependency. This module exists as a separate publication artifact so
 * integrators can depend on `ch.trancee:meshlink-testing` without pulling test utilities into
 * production builds.
 *
 * ## Usage
 *
 * ```kotlin
 * dependencies {
 *     testImplementation("ch.trancee:meshlink-testing:0.1.0")
 * }
 * ```
 *
 * ```kotlin
 * import ch.trancee.meshlink.testing.VirtualMeshTransport
 * import ch.trancee.meshlink.testing.linkedTransports
 * import ch.trancee.meshlink.testing.createForTest
 * import ch.trancee.meshlink.api.MeshLink
 * import ch.trancee.meshlink.api.meshLinkConfig
 *
 * val config = meshLinkConfig("com.example.test") {}
 * val (transportA, transportB) = linkedTransports()
 * val meshA = MeshLink.createForTest(config, transport = transportA)
 * val meshB = MeshLink.createForTest(config, transport = transportB)
 * meshA.start()
 * meshB.start()
 * // transportA and transportB exchange frames as if on real BLE
 * ```
 */
internal object TestingModuleMarker
