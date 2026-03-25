---
name: swift-testing-expert
description: 'Expert guidance for Apple''s Swift Testing framework (import Testing). Use this skill for writing test functions with @Test, validating behavior with #expect and #require, organizing tests into @Suite types, parameterized testing, migrating from XCTest to Swift Testing, confirmations for async events, known issue handling with withKnownIssue, exit tests, tags, traits like .disabled/.enabled/.serialized/.timeLimit, test attachments, and testing patterns for Swift concurrency (actors, async/await, @MainActor). Trigger whenever the user mentions @Test, #expect, #require, @Suite, Swift Testing, import Testing, parameterized tests, test tags, confirmation(), withKnownIssue, exit tests (#expect(processExitsWith:)), Test.cancel, Attachment.record, or asks about migrating XCTest assertions to Swift Testing, even if they don''t say "Swift Testing" explicitly. Do not use for XCTest-only questions that don''t involve migration, for UI testing with XCUITest, or for non-Apple testing frameworks.'
---

# Swift Testing Expert

Help the user write, organize, debug, and migrate tests using Apple's Swift Testing framework. Give direct guidance with working code examples grounded in the official documentation rather than abstract theory.

## Identify the real task first

Swift Testing questions often blend several layers. Figure out which one is actually driving the task:

- Defining and naming tests (`@Test`, `@Suite`)
- Validating behavior (`#expect`, `#require`)
- Parameterized tests (running one test across many inputs)
- Async testing and confirmations
- Test organization (suites, tags, nesting)
- Traits (enabling/disabling, time limits, serialization, bugs)
- Known issues (`withKnownIssue`)
- Exit testing (`#expect(processExitsWith:)`)
- Attachments
- Migrating from XCTest
- Package.swift / test target setup

If version details matter, ask early. The most useful missing details:

- Swift version (Swift Testing requires Swift 6+ / Xcode 16+)
- Whether they're mixing XCTest and Swift Testing in the same target
- The specific error message or unexpected behavior
- Whether the test target is in SPM or an Xcode project

## Working style

- Start with a direct answer, then show code.
- Keep examples minimal but runnable.
- When the user shows failing test code, read the error carefully — Swift's diagnostics and the Testing framework's compile-time checks are often specific.
- Distinguish between Swift Testing patterns and XCTest patterns. Don't mix them unless the user's codebase explicitly does.
- If the user is on a version that doesn't support a feature (e.g., exit tests require Swift 6.2+), say so explicitly and suggest alternatives.

## Defining tests

A test function is any function prefixed with `@Test`. It doesn't need to live inside a class, and its name doesn't need to start with "test":

```swift
import Testing

@Test func foodTruckExists() {
  // test logic here
}
```

Customize the display name by passing a string:

```swift
@Test("Food truck has correct inventory") func inventory() { ... }
```

Test functions can be `async`, `throws`, or both. For main-actor-isolated tests, add `@MainActor`:

```swift
@Test @MainActor func uiStateUpdated() async throws { ... }
```

Limit availability with `@available`:

```swift
@available(macOS 15.0, *)
@Test func newFeature() { ... }
```

Important: only import `Testing` in test targets. The testing library isn't stripped from release binaries, so importing it into production code leaks test logic into your app.

## Validating behavior

Swift Testing replaces XCTest's ~40 assertion functions with two macros:

**`#expect`** — records a failure but keeps the test running:
```swift
#expect(calculator.total(of: [3, 3]) == 6)
#expect(engine.isRunning)
#expect(!list.isEmpty)
```

**`#require`** — throws `ExpectationFailedError` on failure, stopping the test:
```swift
let customer = try #require(Customer(id: 123))  // unwraps or fails
try #require(engine.parts.first != nil)
```

`#require` is also the replacement for `XCTUnwrap` — it unwraps optionals:
```swift
let part = try #require(engine.parts.first)
// part is now non-optional; test stops if nil
```

The macros capture the full expression, so failures give detailed diagnostics. For instance, `#expect(calculator.total(of: [3, 3]) == 7)` prints:
```
Expectation failed: (calculator.total(of: [3, 3]) → 6) == 7
```

### Testing for errors

Check that code throws a specific error:
```swift
#expect(throws: MyError.outOfRange) {
    try order.add(topping: .mozarella, toPizzasIn: -1..<0)
}
```

Check that code throws any error:
```swift
#expect(throws: (any Error).self) {
    try riskyOperation()
}
```

Check that code does NOT throw (without stopping the test on failure):
```swift
#expect(throws: Never.self) {
    try safeOperation()
}
```

Inspect the thrown error for further validation:
```swift
let error = #expect(throws: InvalidToppingError.self) {
    try pizza.add(topping: .marshmallows)
}
#expect(error?.reason == .dessertToppingOnly)
```

Use `#require(throws:)` variants when you want the test to stop on mismatch.

### Recording unconditional failures

Replace `XCTFail("message")` with:
```swift
Issue.record("Engine is not electric")
```

## Organizing tests with suites

Any type containing `@Test` functions is automatically a suite. Annotate with `@Suite` to customize:

```swift
@Suite("Food truck tests") struct FoodTruckTests {
    @Test func exists() { ... }
    @Test func canServeCustomers() { ... }
}
```

Suites can nest:
```swift
@Suite struct FoodTruckTests {
    @Suite struct EngineTests {
        @Test func starts() { ... }
    }
}
```

Key rules:
- **Prefer structs** (or actors) over classes for suites — better concurrency safety.
- Each instance test method runs on a **fresh instance** of the suite type. This replaces XCTest's `setUp()` — use `init()` instead.
- If you need teardown, use a class or actor with `deinit`.
- Suite types must **not** have `@available` restrictions (test functions within them can).
- If a suite has instance test methods, it must have a zero-argument initializer (implicit or explicit, can be `async throws`).

### Setup and teardown

```swift
struct FoodTruckTests {
    var truck: FoodTruck

    init() async throws {
        truck = try await FoodTruck.create()
    }

    @Test func serves() { ... }  // runs on fresh instance
}
```

For teardown, use a class:
```swift
final class FoodTruckTests {
    var truck: FoodTruck

    init() { truck = FoodTruck() }
    deinit { truck.shutdown() }

    @Test func serves() { ... }
}
```

### Traits on suites

Traits applied to a suite are inherited by all tests inside it:
```swift
@Suite(.tags(.critical), .serialized) struct PaymentTests {
    @Test func chargeCard() { ... }    // inherits .critical tag + serial execution
    @Test func refund() { ... }        // same
}
```

## Parameterized tests

Run a single test function across many inputs. Each input becomes a separate test case with its own pass/fail status:

```swift
@Test("All foods available", arguments: Food.allCases)
func foodAvailable(_ food: Food) async throws {
    let truck = FoodTruck(selling: food)
    #expect(await truck.cook(food))
}
```

Works with arrays, ranges, or any `Collection`:
```swift
@Test("Large orders", arguments: 1 ... 100)
func largeOrder(count: Int) async throws { ... }
```

Two-collection parameterization produces the Cartesian product:
```swift
@Test(arguments: Food.allCases, 1 ... 10)
func order(food: Food, count: Int) { ... }  // 5 foods × 10 counts = 50 cases
```

Use `zip()` to pair elements instead:
```swift
@Test(arguments: zip(Food.allCases, 1 ... 5))
func order(food: Food, count: Int) { ... }  // 5 cases, not 25
```

Arguments can use `try` and `await`:
```swift
@Test(arguments: try await Food.bestSelling)
func orderEntree(food: Food) { ... }
```

By default, parameterized cases run in parallel. Add `.serialized` if they need to run sequentially.

For selective re-running of individual cases, arguments should conform to `Codable`, `RawRepresentable` with `Encodable` raw value, or `Identifiable` with `Encodable` ID.

## Async testing and confirmations

Mark test functions `async` and `await` code directly:
```swift
@Test func priceLookup() async {
    let price = await unitPrice(for: .mozarella)
    #expect(price == 3)
}
```

For event-driven async code (callbacks, notifications), use **confirmations**:
```swift
@Test func orderTriggersEvent() async {
    await confirmation() { soldFood in
        FoodTruck.shared.eventHandler = { event in
            if case .soldFood = event { soldFood() }
        }
        await Customer().buy(.soup)
    }
}
```

Confirm an event happens a specific number of times:
```swift
await confirmation(expectedCount: 3) { confirmed in ... }
```

Confirm an event does NOT happen:
```swift
await confirmation(expectedCount: 0) { neverCalled in ... }
```

Use ranges for variable counts:
```swift
await confirmation(expectedCount: 1...) { atLeastOnce in ... }  // at least once
await confirmation(expectedCount: 0 ..< 100) { bounded in ... } // up to 99 times
```

The body must complete before `confirmation()` returns — it doesn't wait indefinitely like `XCTExpectation`. This means the event must fire within the closure's scope.

## Tags

Tags categorize tests across suites and files. They're declared as static members of `Tag`:

```swift
extension Tag {
    @Tag static var critical: Self
    @Tag static var networking: Self
}

@Test(.tags(.critical, .networking)) func apiCall() { ... }
```

Tags on a suite are inherited by all tests within it. Tags don't affect how tests run — they're for organization, filtering, and display.

For uniqueness across packages, use reverse-DNS nesting:
```swift
extension Tag {
    enum com_example_myapp {}
}
extension Tag.com_example_myapp {
    @Tag static var smoke: Tag
}
```

Tags must be declared in an extension of `Tag` (or a type nested in `Tag`). Computed properties and declarations outside `Tag` extensions are not recognized.

## Enabling and disabling tests

Conditionally control whether a test runs using traits:

```swift
@Test(.disabled("Under construction")) func newFeature() { ... }

@Test(.enabled(if: Feature.isAvailable)) func featureTest() { ... }

@Test(.disabled(if: CI.isRunning), .bug(id: "12345"))
func flakyOnCI() { ... }
```

Multiple conditions must all pass for the test to run. If any fails, the test is skipped and the first failing condition is reported.

To stop a running test early without failing:
```swift
@Test func conditional() throws {
    guard let device = Device.current else {
        try Test.cancel("No device available")
    }
    // test continues only if device exists
}
```

## Serialization and parallelism

Tests run in parallel by default (in-process, via task groups). Control this with `.serialized`:

```swift
@Test(.serialized, arguments: Food.allCases)
func prepare(food: Food) { ... }  // cases run one at a time

@Suite(.serialized) struct SharedResourceTests {
    @Test func a() { ... }
    @Test func b() { ... }  // waits for a() to finish
}
```

`.serialized` is recursive — applying it to a suite serializes all nested suites and parameterized cases within it.

## Time limits

Set an upper bound on test execution:

```swift
@Test(.timeLimit(.minutes(5))) func longRunning() async { ... }
```

If exceeded, the test's task is cancelled and a `.timeLimitExceeded` issue is recorded. When applied to a suite, the limit applies to each test individually (not the suite as a whole). For parameterized tests, each case gets its own time limit.

If multiple time limits apply, the shortest wins.

## Known issues

Mark code with known bugs that shouldn't cause test failure:

```swift
@Test func grillHeating() throws {
    var truck = FoodTruck()
    try truck.startGrill()
    withKnownIssue("Propane tank empty") {
        #expect(truck.grill.isHeating)
    }
}
```

Errors thrown inside the closure are also treated as known:
```swift
withKnownIssue {
    try truck.startGrill()       // known to throw
    #expect(truck.grill.isHeating)  // also inside known-issue scope
}
```

Match specific issues:
```swift
try withKnownIssue {
    ...
} matching: { issue in
    guard case .expectationFailed(let exp) = issue.kind else { return false }
    return exp.isRequired
}
```

For intermittent (flaky) failures:
```swift
withKnownIssue(isIntermittent: true) {
    #expect(truck.grill.isHeating)  // sometimes fails, sometimes passes
}
```

Conditional known issues:
```swift
withKnownIssue {
    #expect(truck.grill.isHeating)
} when: {
    !hasPropane
}
```

If no issues are recorded inside `withKnownIssue`, the framework records a separate issue — a signal that the underlying bug may be fixed and you can remove the wrapper.

## Exit testing

_Requires Swift 6.2+ / Xcode 26+._

Test code that calls `precondition()`, `fatalError()`, or otherwise exits the process:

```swift
@Test func rejectsInvalidInput() async {
    await #expect(processExitsWith: .failure) {
        Customer.current.eat(spoiledFood)
    }
}
```

The closure runs in a **child process**, not the test process. Capture state explicitly:
```swift
@Test(arguments: Food.allJunkFood)
func rejectsJunkFood(_ food: Food) async {
    await #expect(processExitsWith: .failure) { [food] in
        Customer.current.eat(food)
    }
}
```

Captured values must conform to `Sendable` and `Codable`.

Check specific exit conditions: `.success`, `.failure`, `.exitCode(N)`, `.signal(N)`.

Gather stdout/stderr from the child process:
```swift
let result = await #expect(
    processExitsWith: .failure,
    observing: [\.standardOutputContent]
) { ... }
if let result {
    #expect(result.standardOutputContent.contains(UInt8(ascii: "E")))
}
```

## Attachments

Attach data to test results for post-run inspection:

```swift
Attachment.record(csvBytes, named: "report.csv")
```

Attach `Encodable` types by conforming to `Attachable`:
```swift
extension SalesReport: Encodable, Attachable {}
Attachment.record(salesReport, named: "report.json")
```

Attach files by URL:
```swift
let attachment = try await Attachment(contentsOf: fileURL)
Attachment.record(attachment)
```

Attach images with format control:
```swift
Attachment.record(image, named: "chart", as: .png)
```

Attachments are saved to:
- **Xcode**: test report
- **VS Code**: `.build/attachments/`
- **SPM CLI**: pass `--attachments-path <dir>` to `swift test`

For custom types, implement `Attachable.withUnsafeBytes(for:_:)`.

## Associating bugs

Link tests to bug trackers:
```swift
@Test(.bug("https://bugs.example.com/12345")) func workaround() { ... }
@Test(.bug(id: "PROJ-789")) func regression() { ... }
@Test(.bug(id: 42, "Crash on empty input")) func emptyCrash() { ... }
```

## Test target setup

### Swift Package Manager

```swift
// Package.swift
let package = Package(
    name: "MyPackage",
    platforms: [.macOS(.v14), .iOS(.v17)],
    targets: [
        .target(name: "MyLibrary"),
        .testTarget(
            name: "MyLibraryTests",
            dependencies: ["MyLibrary"]
        )
    ]
)
```

Run tests: `swift test`

### Xcode project

Add a test target (File → New → Target → Unit Testing Bundle). Xcode 16+ automatically supports Swift Testing alongside XCTest in the same target.

### Mixing XCTest and Swift Testing

Both can coexist in the same test target. Import both modules:
```swift
import XCTest
import Testing
```

However, don't nest `@Test` functions inside `XCTestCase` subclasses or vice versa. Keep them in separate types.

## XCTest migration

When migrating from XCTest to Swift Testing, read `references/xctest-migration.md` for the full assertion equivalence table and step-by-step patterns.

The high-level changes:
1. Replace `XCTestCase` classes with structs
2. Replace `setUp()` / `tearDown()` with `init()` / `deinit`
3. Replace `func testX()` with `@Test func x()`
4. Replace `XCTAssert*` calls with `#expect` / `#require`
5. Replace `XCTestExpectation` with `confirmation()`
6. Replace `XCTSkip` with `.enabled(if:)` / `.disabled()` traits or `Test.cancel()`

## Common patterns and best practices

### Test naming
Give tests descriptive display names. The function name is for code; the string is for humans:
```swift
@Test("Discount applies when cart has 10+ items")
func bulkDiscount() { ... }
```

### One concept per test
Each test should validate one behavior. Use parameterized tests to cover multiple inputs for the same behavior, not multiple assertions for unrelated behaviors.

### Prefer `#require` for preconditions
If a nil value or false condition means the rest of the test is meaningless, use `#require` to stop early rather than scattering nil checks:
```swift
@Test func processOrder() throws {
    let order = try #require(Order.pending.first)
    let receipt = try order.process()
    #expect(receipt.total > 0)
}
```

### Testing actors and concurrency
Test functions can be `async`. Access actor-isolated state with `await`:
```swift
@Test func accountBalance() async {
    let account = BankAccount(balance: 100)
    await account.deposit(50)
    let balance = await account.balance
    #expect(balance == 150)
}
```

### Testing @MainActor code
Isolate the test to the main actor:
```swift
@Test @MainActor func viewModelUpdates() async {
    let vm = ViewModel()
    await vm.refresh()
    #expect(vm.items.count > 0)
}
```

## Reference file

Read `references/xctest-migration.md` when migrating from XCTest or when you need the full assertion equivalence table.
