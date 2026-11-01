import Foundation
import MeshLinkSwift
import MeshLinkTestSupport
import XCTest

final class LifecycleAndErrorsTests: XCTestCase {
    private var fake: FakeMesh!
    private var config: MeshLinkConfig!

    override func setUp() async throws {
        fake = FakeMesh()
        config = try MeshLinkConfig.Builder(preset: Preset.OfflineChat.shared, securityProfile: .classical).meshId("lifecycle").build()
    }

    override func tearDown() async throws { fake.close() }

    func testStart_withBluetoothOff_isBlockedUntilBluetoothIsSwitchedOn() async throws {
        let phone = fake.addHost(name: "phone")
        try await phone.setBluetoothOn(on: false)
        let node = try MeshLink.create(config: config, platform: phone)
        try await node.start()
        let blocked = node.state.value

        try await phone.setBluetoothOn(on: true)

        guard case .blocked(let state) = onEnum(of: blocked) else { return XCTFail("\(blocked)") }
        XCTAssertEqual(state.requirements, [Requirement.BluetoothOff.shared])
        XCTAssertTrue(node.state.value is MeshState.Running)
    }

    func testStart_onUnsupportedPlatform_isBlockedWithUnsupported() async throws {
        let simulator = fake.addHost(name: "simulator")
        try await simulator.setSupported(isSupported: false)
        let node = try MeshLink.create(config: config, platform: simulator)

        try await node.start()

        XCTAssertEqual(node.state.value, MeshState.Blocked(requirements: [Requirement.Unsupported.shared]))
    }

    func testSend_whileStopped_isRejectedWithNotRunning() throws {
        let node = try MeshLink.create(config: config, platform: fake.addHost(name: "phone"))

        let result = node.send(to: fake.addHost(name: "other").nodeId, data: Data([1]))

        guard case .rejected(let rejected) = onEnum(of: result) else { return XCTFail("\(result)") }
        XCTAssertEqual(rejected.reason, .notRunning)
        XCTAssertEqual(rejected.reason.code, 100)
    }

    func testBuild_meshKeyWithoutSecuredProfile_throwsInvalidConfig() {
        XCTAssertThrowsError(try MeshLinkConfig.Builder(preset: Preset.OfflineChat.shared, securityProfile: .none)
            .meshId("open").meshKey(Data(count: 32)).build()) { error in
            guard let exception = (error as NSError).kotlinException as? MeshLinkException else { return XCTFail("\(error)") }
            guard case .invalidConfig(let invalid) = onEnum(of: exception) else { return XCTFail("\(exception)") }
            XCTAssertEqual(invalid.setting, "meshKey")
        }
    }

    func testCreate_whileAnotherInstanceIsOpen_throwsInstanceAlreadyOpen() throws {
        let phone = fake.addHost(name: "phone")
        _ = try MeshLink.create(config: config, platform: phone)

        XCTAssertThrowsError(try MeshLink.create(config: config, platform: phone)) { error in
            XCTAssertTrue((error as NSError).kotlinException is MeshLinkException.InstanceAlreadyOpen)
        }
    }

    func testStart_afterClose_throwsClosed() async throws {
        let node = try MeshLink.create(config: config, platform: fake.addHost(name: "phone"))
        node.close()

        do {
            try await node.start()
            XCTFail("expected Closed")
        } catch {
            XCTAssertTrue((error as NSError).kotlinException is MeshLinkException.Closed)
        }
    }

    func testNodeId_isUsableAsDictionaryKeyAcrossInstances() throws {
        let phone = fake.addHost(name: "phone")
        let parsed = try NodeId.companion.fromHex(hex: phone.nodeId.toHex())

        let names = [phone.nodeId: "phone"]

        XCTAssertFalse(parsed === phone.nodeId)
        XCTAssertEqual(names[parsed], "phone")
        XCTAssertEqual(Set([parsed, phone.nodeId]).count, 1)
    }

    func testPeersIteration_cancellingTheTaskEndsTheLoop() async throws {
        let node = try MeshLink.create(config: config, platform: fake.addHost(name: "phone"))
        let loop = Task { () -> Int in
            var seen = 0
            for await _ in node.peers { seen += 1 }
            return seen
        }
        try await Task.sleep(nanoseconds: 100_000_000)

        loop.cancel()

        let seen = try await within(2) { await loop.value }
        XCTAssertEqual(seen, 1)
    }

    func testFlowIteration_onMainActor_resumesOnMainThread() async throws {
        let phone = fake.addHost(name: "phone")
        let node = try MeshLink.create(config: config, platform: phone)
        try await node.start()

        let onMain = try await within { @MainActor () -> Bool in
            for await _ in node.state { return pthread_main_np() != 0 }
            return false
        }

        XCTAssertTrue(onMain)
    }
}
