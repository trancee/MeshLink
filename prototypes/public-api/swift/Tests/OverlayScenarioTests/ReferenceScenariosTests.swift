import Foundation
import MeshLinkSwift
import MeshLinkTestSupport
import XCTest

/// The five reference scenarios through SKIE plus the MeshLinkSwift overlay.
@MainActor
final class ReferenceScenariosTests: XCTestCase {
    private var fake: FakeMesh!

    override func setUp() async throws { fake = FakeMesh() }
    override func tearDown() async throws { fake.close() }

    // region offline-chat
    func testOfflineChat_routedPeer_receivesMessageAndSendsReceipts() async throws {
        let (alicePhone, relayPhone, bobPhone) = (fake.addHost(name: "alice"), fake.addHost(name: "relay"), fake.addHost(name: "bob"))
        try await fake.link(first: alicePhone, second: relayPhone, rssiDbm: -60)
        try await fake.link(first: relayPhone, second: bobPhone, rssiDbm: -60)
        let config = try meshLinkConfig(preset: Preset.OfflineChat.shared, securityProfile: .classical) { $0.meshId = "family-chat" }
        let alice = try MeshLink.create(config: config, platform: alicePhone)
        let bob = try MeshLink.create(config: config, platform: bobPhone)
        try await MeshLink.create(config: config, platform: relayPhone).start()
        try await alice.start()
        try await bob.start()

        let peers = try await first(alice.peers) { $0.contains { $0.nodeId == bob.localNodeId } }
        let sent = alice.send(to: bob.localNodeId, data: Data("Hi Bob".utf8), options: SendOptions(deliveryReceipt: true, readReceipt: true))
        guard case .accepted(let accepted) = onEnum(of: sent) else { return XCTFail("rejected: \(sent)") }
        let received = try await first(bob.messages)
        _ = try await first(accepted.message.status) { $0 is DeliveryStatus.Delivered }
        received.markRead()
        _ = try await first(accepted.message.status) { $0 is DeliveryStatus.Read }

        let bobAsPeer = try XCTUnwrap(peers.first { $0.nodeId == bob.localNodeId })
        guard case .routed(let routed) = onEnum(of: bobAsPeer.reachability) else { return XCTFail("\(bobAsPeer)") }
        XCTAssertEqual(routed.hops, 2)
        XCTAssertEqual(String(decoding: received.data, as: UTF8.self), "Hi Bob")
        XCTAssertEqual(received.sender, alice.localNodeId)
    }
    // endregion

    // region emergency-broadcast
    func testEmergencyBroadcast_reachesNodesWithinHopLimitOnly() async throws {
        let phones = (1...5).map { fake.addHost(name: "phone\($0)") }
        for (first, second) in zip(phones, phones.dropFirst()) { try await fake.link(first: first, second: second, rssiDbm: -70) }
        let config = try meshLinkConfig(preset: Preset.EmergencyBroadcast.shared, securityProfile: .classical) { $0.meshId = "valley-alerts" }
        var nodes: [MeshLink] = []
        for phone in phones { let node = try MeshLink.create(config: config, platform: phone); try await node.start(); nodes.append(node) }

        let sent = nodes[0].broadcast(Data("Flood warning".utf8), hopLimit: 3)
        var hopCounts: [Int32] = []
        for node in nodes[1...3] { hopCounts.append(try await first(node.messages).hops) }
        let beyondLimit = try? await first(nodes[4].messages, timeout: 0.5)

        guard case .accepted = onEnum(of: sent) else { return XCTFail("\(sent)") }
        XCTAssertEqual(hopCounts, [1, 2, 3])
        XCTAssertNil(beyondLimit)
    }
    // endregion

    // region proximity-discovery
    func testProximityDiscovery_bucketsDirectPeersAndLeavesRoutedUnknown() async throws {
        let me = fake.addHost(name: "me")
        let (close, sameRoom, distant, routed) = (fake.addHost(name: "close"), fake.addHost(name: "same-room"), fake.addHost(name: "distant"), fake.addHost(name: "routed"))
        try await fake.link(first: me, second: close, rssiDbm: -45)
        try await fake.link(first: me, second: sameRoom, rssiDbm: -65)
        try await fake.link(first: me, second: distant, rssiDbm: -85)
        try await fake.link(first: close, second: routed, rssiDbm: -50)
        let config = try meshLinkConfig(preset: Preset.ProximityDiscovery.shared, securityProfile: .classical) { $0.meshId = "meetup" }
        let node = try MeshLink.create(config: config, platform: me)
        for host in [close, sameRoom, distant, routed] { try await MeshLink.create(config: config, platform: host).start() }
        try await node.start()

        try await fake.reportRssi(first: me, second: distant, rssiDbm: -50)
        let buckets = Dictionary(uniqueKeysWithValues: node.peers.value.map { ($0.nodeId, $0.proximity.bucket) })

        XCTAssertEqual(buckets, [close.nodeId: .immediate, sameRoom.nodeId: .near, distant.nodeId: .near, routed.nodeId: .unknown])
    }
    // endregion

    // region local-multiplayer
    func testLocalMultiplayer_customPresetDeliversMovesBetweenFourPlayersQuickly() async throws {
        let phones = (1...4).map { fake.addHost(name: "player\($0)") }
        for (index, phone) in phones.enumerated() { for other in phones[(index + 1)...] { try await fake.link(first: phone, second: other, rssiDbm: -50) } }
        let arena = Preset.Custom(name: "Arena", base: Preset.LocalMultiplayer.shared, overrides: PresetOverrides.Builder().maxPayloadBytes(bytes: 64).build())
        let config = try meshLinkConfig(preset: arena, securityProfile: .classical) { $0.meshId = "arena-42" }
        var players: [MeshLink] = []
        for phone in phones { let player = try MeshLink.create(config: config, platform: phone); try await player.start(); players.append(player) }
        let startedAt = Date()

        for player in players { for other in players where other !== player { _ = player.send(to: other.localNodeId, data: Data([1, 2]), options: SendOptions(directOnly: true)) } }
        var movesPerPlayer: [Int] = []
        for player in players { var moves = 0; for _ in 1...3 { _ = try await first(player.messages); moves += 1 }; movesPerPlayer.append(moves) }
        let elapsed = Date().timeIntervalSince(startedAt)

        XCTAssertEqual(movesPerPlayer, [3, 3, 3, 3])
        XCTAssertLessThan(elapsed, 0.5)
        XCTAssertTrue(config.describe().contains("maxPayloadBytes: 64 (override)"))
    }
    // endregion

    // region field-teams
    func testFieldTeams_heldMessageIsDeliveredWhenPeerReturns() async throws {
        let (basePhone, scoutPhone) = (fake.addHost(name: "base"), fake.addHost(name: "scout"))
        try await fake.link(first: basePhone, second: scoutPhone, rssiDbm: -80)
        let config = try meshLinkConfig(preset: Preset.FieldTeams.shared, securityProfile: .postQuantum) { $0.meshId = "search-team" }
        let base = try MeshLink.create(config: config, platform: basePhone)
        let scout = try MeshLink.create(config: config, platform: scoutPhone)
        try await base.start()
        try await scout.start()
        try await scoutPhone.setPresent(isPresent: false)

        let sent = base.send(to: scout.localNodeId, data: Data("Regroup at hut".utf8), options: SendOptions(deliveryReceipt: true))
        guard case .accepted(let accepted) = onEnum(of: sent) else { return XCTFail("\(sent)") }
        _ = try await first(accepted.message.status) { $0 is DeliveryStatus.Held }
        try await scoutPhone.setPresent(isPresent: true)
        let received = try await first(scout.messages)

        _ = try await first(accepted.message.status) { $0 is DeliveryStatus.Delivered }
        XCTAssertEqual(String(decoding: received.data, as: UTF8.self), "Regroup at hut")
    }

    func testFieldTeams_heldMessageExpiresWhenLifetimePasses() async throws {
        let (basePhone, scoutPhone) = (fake.addHost(name: "base"), fake.addHost(name: "scout"))
        let config = try meshLinkConfig(preset: Preset.FieldTeams.shared, securityProfile: .postQuantum) { $0.meshId = "search-team" }
        let base = try MeshLink.create(config: config, platform: basePhone)
        let scout = try MeshLink.create(config: config, platform: scoutPhone)
        try await base.start()

        let sent = base.send(to: scout.localNodeId, data: Data("Regroup at hut".utf8), options: SendOptions(messageLifetime: 1))
        guard case .accepted(let accepted) = onEnum(of: sent) else { return XCTFail("\(sent)") }
        _ = try await first(accepted.message.status) { $0 is DeliveryStatus.Held }

        _ = try await first(accepted.message.status, timeout: 3) { $0 is DeliveryStatus.Expired }
    }
    // endregion
}
