# XCTest to Swift Testing Migration Guide

Complete reference for migrating test code from XCTest to Swift Testing.

## Assertion equivalence table

| XCTest | Swift Testing |
|---|---|
| `XCTAssert(x)`, `XCTAssertTrue(x)` | `#expect(x)` |
| `XCTAssertFalse(x)` | `#expect(!x)` |
| `XCTAssertNil(x)` | `#expect(x == nil)` |
| `XCTAssertNotNil(x)` | `#expect(x != nil)` |
| `XCTAssertEqual(x, y)` | `#expect(x == y)` |
| `XCTAssertNotEqual(x, y)` | `#expect(x != y)` |
| `XCTAssertIdentical(x, y)` | `#expect(x === y)` |
| `XCTAssertNotIdentical(x, y)` | `#expect(x !== y)` |
| `XCTAssertGreaterThan(x, y)` | `#expect(x > y)` |
| `XCTAssertGreaterThanOrEqual(x, y)` | `#expect(x >= y)` |
| `XCTAssertLessThan(x, y)` | `#expect(x < y)` |
| `XCTAssertLessThanOrEqual(x, y)` | `#expect(x <= y)` |
| `XCTAssertThrowsError(try f())` | `#expect(throws: (any Error).self) { try f() }` |
| `XCTAssertThrowsError(try f()) { error in … }` | `let error = #expect(throws: (any Error).self) { try f() }` |
| `XCTAssertNoThrow(try f())` | `#expect(throws: Never.self) { try f() }` |
| `try XCTUnwrap(x)` | `try #require(x)` |
| `XCTFail("…")` | `Issue.record("…")` |

**Note:** There's no direct equivalent of `XCTAssertEqual(_:_:accuracy:)`. Use `isApproximatelyEqual()` from [swift-numerics](https://github.com/apple/swift-numerics).

## Structural migration patterns

### Test classes → structs

```swift
// Before
class FoodTruckTests: XCTestCase {
    func testEngineWorks() { ... }
}

// After
struct FoodTruckTests {
    @Test func engineWorks() { ... }
}
```

Prefer structs or actors over classes — the compiler can better enforce concurrency safety. Use `@Suite` if you want a custom display name or to apply traits.

### setUp / tearDown → init / deinit

```swift
// Before
class FoodTruckTests: XCTestCase {
    var batteryLevel: NSNumber!
    override func setUp() async throws {
        batteryLevel = 100
    }
    override func tearDown() {
        batteryLevel = 0
    }
}

// After (struct, no teardown needed)
struct FoodTruckTests {
    var batteryLevel: NSNumber
    init() async throws {
        batteryLevel = 100
    }
}

// After (class, when teardown is needed)
final class FoodTruckTests {
    var batteryLevel: NSNumber
    init() async throws { batteryLevel = 100 }
    deinit { batteryLevel = 0 }
}
```

Each test method runs on a fresh instance, so `init()` replaces `setUp()` naturally.

### Test method naming

```swift
// Before
func testEngineWorks() { ... }

// After
@Test func engineWorks() { ... }
// or with display name:
@Test("Engine works correctly") func engineWorks() { ... }
```

The `test` prefix is not required or recommended. The `@Test` attribute is the identifier.

### Main actor behavior

XCTest runs synchronous test methods on the main actor by default. Swift Testing runs all tests on an arbitrary task. If your test must run on the main thread:

```swift
@Test @MainActor func uiUpdate() async { ... }
// or use MainActor.run for specific sections:
@Test func mixedContext() async {
    await MainActor.run {
        // main-thread work
    }
}
```

### Expectations → confirmations

```swift
// Before
func testTruckEvents() async {
    let soldFood = expectation(description: "sold food")
    FoodTruck.shared.eventHandler = { event in
        if case .soldFood = event { soldFood.fulfill() }
    }
    await Customer().buy(.soup)
    await fulfillment(of: [soldFood])
}

// After
@Test func truckEvents() async {
    await confirmation("sold food") { soldFood in
        FoodTruck.shared.eventHandler = { event in
            if case .soldFood = event { soldFood() }
        }
        await Customer().buy(.soup)
    }
}
```

Key differences:
- `confirmation()` doesn't suspend waiting — the event must happen within the closure's scope.
- Expected count defaults to 1 (same as `XCTestExpectation`).
- Use `expectedCount: 0` for "must not happen" (replaces inverted expectations).
- Use a range like `10...` for "at least N" (replaces `assertForOverFulfill = false`).

### Skipping → conditions and cancellation

```swift
// Before
func testArepas() throws {
    try XCTSkipIf(CashRegister.isEmpty)
    try XCTSkipUnless(FoodTruck.sells(.arepas))
    ...
}

// After (pre-run conditions as traits)
@Suite(.disabled(if: CashRegister.isEmpty))
struct FoodTruckTests {
    @Test(.enabled(if: FoodTruck.sells(.arepas)))
    func arepas() { ... }
}

// After (mid-test cancellation)
@Test func cashRegister() throws {
    let drawer = CashRegister().open()
    if drawer.isEmpty {
        try Test.cancel("Cash register is empty")
    }
    ...
}
```

### XCTExpectFailure → withKnownIssue

```swift
// Before
func testGrill() {
    XCTExpectFailure("Grill is out of fuel") {
        try FoodTruck.shared.grill.start()
    }
}

// After
@Test func grill() {
    withKnownIssue("Grill is out of fuel") {
        try FoodTruck.shared.grill.start()
    }
}
```

### continueAfterFailure → #require

```swift
// Before
func testTruck() async {
    continueAfterFailure = false
    XCTAssertTrue(FoodTruck.shared.isLicensed)
    ...
}

// After
@Test func truck() throws {
    try #require(FoodTruck.shared.isLicensed)
    // test stops here if not licensed
    ...
}
```

## Migration strategy

1. **Start with leaf tests** — tests with few dependencies on XCTest infrastructure.
2. **Migrate one file at a time** — both frameworks coexist in the same target.
3. **Don't nest `@Test` in `XCTestCase`** — keep Swift Testing functions in separate types.
4. **Migrate assertions first** — the structural changes (class→struct, setUp→init) can follow.
5. **Validate continuously** — run `swift test` after each file to catch regressions early.
6. **Keep XCTest for UI testing** — `XCUITest` has no Swift Testing equivalent.
