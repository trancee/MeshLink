---
name: aaa-testing
description: Structure tests using the AAA (Arrange, Act, Assert) pattern for clarity, readability, and maintainability. Applies to unit tests, integration tests, and API tests. Use when writing tests, reviewing test code, refactoring tests, or when asked to "use AAA", "structure tests", "write readable tests", "apply Arrange Act Assert", or "test this API".
---

<objective>
Apply the AAA (Arrange, Act, Assert) pattern to produce well-structured, readable tests — unit, integration, or API. Each test method is divided into three clearly separated sections — setup, execution, verification — making tests easier to write, understand, debug, and maintain.
</objective>

<quick_start>
Every test follows three sections, separated by blank lines:

```
// Arrange — set the stage
[create instances, define inputs, configure mocks, set expected values]

// Act — execute the thing under test
[call the method/function, capture the result]

// Assert — verify the outcome
[compare actual vs expected, check side effects, verify exceptions]
```

**Rules:**
- One logical action per test (single Act)
- Comments (`// Arrange`, `// Act`, `// Assert`) are optional but aid scanning
- Arrange can be empty if the method needs no setup
- Assert can verify return values, state changes, or thrown exceptions
</quick_start>

<the_pattern>

<arrange>
**Purpose:** Prepare everything the Act step needs.

**What goes here:**
- Instantiate the system under test (SUT) or HTTP client
- Define input values, request payloads, and expected outputs
- Configure mocks, stubs, or fakes for dependencies
- Set initial state (database seeds, file fixtures, config)
- For API tests: construct URLs, headers, request objects

**Guidelines:**
- Keep it minimal — only what this specific test needs
- Extract shared setup to framework hooks (`@BeforeEach`, `beforeEach`, `setUp`) when multiple tests share identical arrangement
- Define the expected value here (not inline in the assert) for clarity
- If Arrange is complex, the SUT may have too many dependencies — consider refactoring production code
</arrange>

<act>
**Purpose:** Execute exactly one behaviour of the SUT.

**What goes here:**
- A single method/function call on the SUT
- For API tests: execute the HTTP request and capture the response
- Capture the return value (if any)

**Guidelines:**
- Exactly one logical action — if you need two calls, you likely need two tests
- For void methods, the Act triggers a side effect verified in Assert
- For exception-testing, wrap the call in the framework's `assertThrows` / `expect(...).toThrow()` equivalent
- For API tests, a single request = one Act; don't chain multiple calls
</act>

<assert>
**Purpose:** Verify the Act produced the correct outcome.

**What goes here:**
- Equality checks (`assertEquals`, `expect(...).toBe(...)`)
- State assertions (`assertTrue`, `assertNull`, object property checks)
- Interaction verification (mock was called with expected args)
- Exception assertions (correct type and message thrown)
- For API tests: status code, response body content, headers, schema shape

**Guidelines:**
- Prefer one logical assertion per test (multiple related asserts on the same object are fine)
- Use descriptive failure messages so a red test explains itself
- Never put additional Act logic after the Assert boundary
- For API tests, assert status code first, then body — a wrong status makes body checks meaningless
</assert>

</the_pattern>

<cleanup_guidance>
**The "Fourth A" — cleanup — stays outside the test body.**

Use framework lifecycle hooks:
- JUnit: `@AfterEach` / `@AfterAll`
- Jest/Vitest: `afterEach` / `afterAll`
- pytest: `yield` fixtures or `teardown_method`
- Go: `t.Cleanup()`

This keeps the test method focused purely on Arrange → Act → Assert.
</cleanup_guidance>

<examples>

<example_java>
```java
@Test
void shouldCalculateDiscountForPremiumCustomer() {
    // Arrange
    var customer = new Customer(CustomerTier.PREMIUM);
    var order = new Order(List.of(new Item("Widget", 100.00)));
    var pricing = new PricingService();
    double expectedTotal = 80.00; // 20% discount

    // Act
    double actualTotal = pricing.calculateTotal(order, customer);

    // Assert
    assertEquals(expectedTotal, actualTotal, 0.01,
        "Premium customers should receive 20% discount");
}
```
</example_java>

<example_typescript>
```typescript
it('should reject login with invalid credentials', async () => {
  // Arrange
  const authService = new AuthService(mockUserRepo);
  const credentials = { email: 'user@test.com', password: 'wrong' };
  mockUserRepo.findByEmail.mockResolvedValue(fakeUser);

  // Act
  const result = authService.login(credentials);

  // Assert
  await expect(result).rejects.toThrow(InvalidCredentialsError);
});
```
</example_typescript>

<example_python>
```python
def test_parse_csv_with_missing_fields():
    # Arrange
    raw_csv = "name,age\nAlice,\nBob,30"
    parser = CsvParser(strict=False)

    # Act
    records = parser.parse(raw_csv)

    # Assert
    assert len(records) == 2
    assert records[0].age is None
    assert records[1].age == 30
```
</example_python>

<example_go>
```go
func TestTransferInsufficientFunds(t *testing.T) {
    // Arrange
    from := account.New("Alice", 50.00)
    to := account.New("Bob", 0.00)
    svc := transfer.NewService()

    // Act
    err := svc.Transfer(from, to, 100.00)

    // Assert
    assert.ErrorIs(t, err, transfer.ErrInsufficientFunds)
    assert.Equal(t, 50.00, from.Balance()) // unchanged
}
```
</example_go>

<example_kotlin_api>
```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

const val BASE_URL = "http://localhost:8000"

class TodoApiTest {

    @Test
    fun testGetAllTodos() {
        // Arrange
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("$BASE_URL/todos")
            .build()

        // Act
        val response = client.newCall(request).execute()

        // Assert
        assertEquals(200, response.code)
        val body = response.body?.string()
        assertNotNull(body)
        assertTrue(body!!.isNotEmpty(), "Response body should contain todo items")
    }
}
```
</example_kotlin_api>

</examples>

<api_testing>
**Applying AAA to API / integration tests:**

The pattern is identical — what changes is what each phase contains:

| Phase | Unit Test | API Test |
|-------|-----------|----------|
| **Arrange** | Create objects, set mocks | Build URL, headers, request body, configure HTTP client |
| **Act** | Call a method | Execute the HTTP request |
| **Assert** | Check return value / state | Check status code, response body, headers |

**API-specific guidance:**
- Keep the HTTP client instantiation in Arrange (or in `@BeforeEach` if shared)
- One request per test — don't chain `POST` then `GET` in a single test
- Assert status code before body content — a 500 makes body assertions meaningless
- For response bodies: parse JSON/XML in the Assert phase, then assert on the parsed structure
- Use descriptive test names that reflect the endpoint and scenario: `testGetAllTodos`, `testCreateUser_DuplicateEmail_Returns409`
</api_testing>

<smells>
**Signs a test violates AAA:**

- Multiple Act steps → split into separate tests
- Assert before Act → test is verifying setup, not behaviour
- Arrange is 30+ lines → SUT has too many dependencies or test needs a builder/factory helper
- No clear visual separation → add blank lines or section comments
- Cleanup code mixed into the test body → move to lifecycle hooks
- Act + Assert interleaved (act-assert-act-assert) → split into focused tests
</smells>

<success_criteria>
Tests written with this skill:
- Have three visually distinct sections (blank-line separated or commented)
- Contain exactly one logical Act per test
- Define expected values in Arrange, not inline in Assert
- Use descriptive assertion messages
- Keep cleanup in framework hooks, not in the test body
- Are self-documenting — a reader understands intent without reading production code
- For API tests: assert status code first, then body structure
</success_criteria>
