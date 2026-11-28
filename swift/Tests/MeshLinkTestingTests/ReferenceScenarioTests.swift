import MeshLink
import MeshLinkTesting
import XCTest

// The reference scenarios of meshlink-testing's reference-scenarios.feature, against the Swift fake
// (ADR 0017 decision 6). Each test names its scenario with `scenario(_:)`; tools/check-scenario-parity.py
// fails when these titles and the feature's differ.
@MainActor
final class ReferenceScenarioTests: XCTestCase {
    private let mesh = FakeMesh()

    func testOfflineChat() async throws {
        scenario("Offline chat: a message routed over a relay becomes Delivered and then Read")
        // Given Alice, a relay and Bob run OfflineChat in a classical mesh, with the relay between them
        let (alice, relay, bob) = try (node("Alice", .OfflineChat.shared), node("Relay", .OfflineChat.shared), node("Bob", .OfflineChat.shared))
        mesh.connect(alice, relay)
        mesh.connect(relay, bob)
        for node in [alice, relay, bob] { try await node.start() }

        // When Alice sends "Hi Bob" to Bob and Bob marks it read
        let message = try accepted(alice.send(Data("Hi Bob".utf8), to: bob.id))
        var inbox = bob.receivedMessages.makeAsyncIterator()
        let next = await inbox.next()
        let received = try XCTUnwrap(next)
        var statuses = message.statusUpdates.makeAsyncIterator()
        received.markRead()

        // Then Bob, who is not Alice's neighbor, received "Hi Bob" from Alice
        XCTAssertFalse(alice.currentPeers.contains { $0.id == bob.id })
        XCTAssertEqual(received.sender, alice.id)
        XCTAssertEqual(received.payload, Data("Hi Bob".utf8))
        // And Alice's message "Hi Bob" went from Delivered to Read
        let first = await statuses.next()
        let second = await statuses.next()
        XCTAssertEqual([first, second], [.delivered, .read])
    }

    func testEmergencyBroadcast() async throws {
        scenario("Emergency broadcast: an alert reaches the nodes within its hop limit only")
        // Given five nodes run EmergencyBroadcast in a classical line
        let nodes = try (1...5).map { try node("Node \($0)", .EmergencyBroadcast.shared) }
        for (first, second) in zip(nodes, nodes.dropFirst()) { mesh.connect(first, second) }
        for node in nodes { try await node.start() }

        // When the first node broadcasts "Flood warning" with a hop limit of 3
        _ = try accepted(nodes[0].broadcast(Data("Flood warning".utf8), hopLimit: 3))

        // Then the next three nodes received "Flood warning" from the first node, and the fifth received nothing
        for node in nodes[1...3] {
            XCTAssertEqual(node.arrived.map(\.payload), [Data("Flood warning".utf8)])
            XCTAssertEqual(node.arrived.map(\.sender), [nodes[0].id])
        }
        XCTAssertTrue(nodes[4].arrived.isEmpty)
    }

    func testProximityDiscovery() async throws {
        scenario("Proximity discovery: each neighbor carries the proximity bucket of its signal strength")
        // Given three nodes run ProximityDiscovery, at -45, -65 and -85 dBm from where Alice will be
        let alice = try node("Alice", .ProximityDiscovery.shared)
        let others = try (1...3).map { try node("Node \($0)", .ProximityDiscovery.shared) }
        for (other, rssi) in zip(others, [-45.0, -65, -85]) {
            mesh.connect(alice, other, rssi: rssi)
            try await other.start()
        }

        // When Alice starts ProximityDiscovery among them
        try await alice.start()

        // Then Alice sees those neighbors Immediate, Near and Far
        let buckets = others.map { other in alice.currentPeers.first { $0.id == other.id }?.proximity?.bucket }
        XCTAssertEqual(buckets, [.immediate, .near, .far])
    }

    func testLocalMultiplayer() async throws {
        scenario("Local multiplayer: four players on a custom preset exchange moves directly")
        // Given four players run the custom preset "Arena", based on LocalMultiplayer, all in range of each other
        let arena = Preset.Custom(name: "Arena", base: Preset.LocalMultiplayer.shared) { $0.messageLifetimeSeconds = 120 }
        let players = try (1...4).map { try node("Player \($0)", arena) }
        for (index, player) in players.enumerated() {
            for other in players[(index + 1)...] { mesh.connect(player, other) }
        }
        for player in players { try await player.start() }
        let started = mesh.now

        // When every player sends a move directly to every other player
        for player in players {
            for other in players where other !== player {
                _ = try accepted(player.send(Data("move".utf8), to: other.id, options: MeshSendOptions(directOnly: true)))
            }
        }

        // Then every player received a move from each of the 3 others within 1 second
        for player in players {
            let senders = Set(player.arrived.map(\.sender))
            XCTAssertEqual(senders, Set(players.filter { $0 !== player }.map(\.id)))
        }
        XCTAssertLessThanOrEqual(mesh.now.timeIntervalSince(started), 1)
    }

    func testFieldTeamsDelivery() async throws {
        scenario("Field teams: a message held while the scout is away is delivered when the scout returns")
        // Given the base and a scout run FieldTeams in a post-quantum mesh, and the base sent "Regroup at hut" to the scout while the scout was away
        let (base, scout, statuses) = try await fieldTeamsWithHeldMessage()

        // When the scout returns
        mesh.connect(base, scout)
        var inbox = scout.receivedMessages.makeAsyncIterator()
        let received = await inbox.next()

        // Then the scout received "Regroup at hut", and the base's message was Held, then Sent and Delivered
        XCTAssertEqual(received?.payload, Data("Regroup at hut".utf8))
        var iterator = statuses.makeAsyncIterator()
        var seen: [MeshDeliveryStatus?] = []
        for _ in 0..<3 { seen.append(await iterator.next()) }
        XCTAssertEqual(seen, [.held, .sent, .delivered])
    }

    func testFieldTeamsExpiry() async throws {
        scenario("Field teams: a held message expires when its lifetime passes")
        // Given the base and a scout run FieldTeams in a post-quantum mesh, and the base sent "Regroup at hut" to the scout while the scout was away
        let (_, scout, statuses) = try await fieldTeamsWithHeldMessage()

        // When the hour-long lifetime of the message passes
        mesh.advance(by: 3600 + 31)

        // Then the scout received nothing, and the base's message went from Held to Expired
        XCTAssertTrue(scout.arrived.isEmpty)
        var iterator = statuses.makeAsyncIterator()
        let first = await iterator.next()
        let second = await iterator.next()
        XCTAssertEqual([first, second], [.held, .expired])
    }

    private func fieldTeamsWithHeldMessage() async throws -> (FakeMeshNode, FakeMeshNode, AsyncStream<MeshDeliveryStatus>) {
        let base = try node("Base", .FieldTeams.shared, profile: SecurityProfilePostQuantum.shared)
        let scout = try node("Scout", .FieldTeams.shared, profile: SecurityProfilePostQuantum.shared)
        try await base.start()
        try await scout.start()
        let message = try accepted(base.send(Data("Regroup at hut".utf8), to: scout.id))
        return (base, scout, message.statusUpdates)
    }

    private func node(_ name: String, _ preset: Preset, profile: SecurityProfile = SecurityProfileClassical.shared) throws -> FakeMeshNode {
        let config = try meshLinkConfig(preset: preset, securityProfile: profile) { $0.meshId = "Reference" }.build()
        return mesh.node(name, config: config)
    }

    private func accepted(_ result: MeshSendResult, file: StaticString = #filePath, line: UInt = #line) throws -> MeshOutgoingMessage {
        guard case .accepted(let message) = result else {
            XCTFail("expected accepted, got \(result)", file: file, line: line)
            throw CancellationError()
        }
        return message
    }

    // The scenario's title in reference-scenarios.feature; tools/check-scenario-parity.py reads it.
    private func scenario(_ title: String) {}
}
