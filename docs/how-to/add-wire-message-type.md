# How to Add a New Wire Message Type

## When to use this

You need to extend the MeshLink protocol with a new message type (e.g., a new control message, a status beacon, etc.).

## Steps

### 1. Assign a type byte

In `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/WireCodec.kt`, add a new constant:

```kotlin
internal const val TYPE_YOUR_MESSAGE: Byte = 0x0D  // next available after 0x0C
```

Type bytes are 1-indexed starting at 0x01. Check the existing constants to find the next free slot.

### 2. Create the message data class

In `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/wire/messages/`:

```kotlin
internal data class YourMessage(
    val fieldOne: ByteArray,
    val fieldTwo: UInt,
) : WireMessage {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YourMessage) return false
        if (!fieldOne.contentEquals(other.fieldOne)) return false
        if (fieldTwo != other.fieldTwo) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fieldOne.contentHashCode()
        result = 31 * result + fieldTwo.hashCode()
        return result
    }
}
```

**Critical:** Any data class with `ByteArray` fields must override `equals()`/`hashCode()` using `contentEquals()`. This is required for Kover branch coverage.

### 3. Implement encode/decode

In `WireCodec.kt`, add encode and decode methods:

```kotlin
internal fun encodeYourMessage(msg: YourMessage): ByteArray {
    val writer = WriteBuffer(estimatedSize)
    writer.startTable(2)  // number of fields
    writer.addBytes(0, msg.fieldOne)
    writer.addUInt(1, msg.fieldTwo)
    val tableOffset = writer.endTable()
    writer.finish(tableOffset)
    return writer.toByteArray(TYPE_YOUR_MESSAGE)
}

internal fun decodeYourMessage(buf: ReadBuffer): YourMessage {
    val table = buf.rootTable()
    return YourMessage(
        fieldOne = table.getBytes(0) ?: ByteArray(0),
        fieldTwo = table.getUInt(1),
    )
}
```

### 4. Register in dispatch

In the `decode()` dispatch function:

```kotlin
TYPE_YOUR_MESSAGE -> decodeYourMessage(ReadBuffer(data, offset = 1))
```

### 5. Add InboundValidator rules

In `InboundValidator.kt`, add structural validation:

```kotlin
TYPE_YOUR_MESSAGE -> validateYourMessage(buf)
```

Validate minimum sizes, required fields, and any field-level constraints.

### 6. Write tests

Create `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/YourMessageTest.kt`:

- Round-trip encode → decode with all fields populated
- Round-trip with optional fields absent (default values)
- Golden byte vector test (encode → assert exact bytes)
- InboundValidator rejection tests for malformed inputs
- equals/hashCode branch coverage (test each field difference position)

### 7. Handle in DeliveryPipeline

In `DeliveryPipeline.kt`, add dispatch logic in the `processInboundFrame()` function:

```kotlin
is YourMessage -> handleYourMessage(frame.source, msg)
```

### 8. Run verification

```bash
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck
```

All three must pass. If `apiCheck` fails, you've accidentally exposed internal types — check visibility modifiers.

## Gotchas

- The FlatBuffers codec is pure Kotlin — no `.fbs` schema files, no flatc codegen
- Sealed interface implementations must be in the same package as the sealed type (not a sub-package)
- Never use `require()` with string interpolation — use explicit `if (...) throw` for Kover compatibility
- Forward compatibility: new optional fields can be added to existing messages via vtable slot extension
