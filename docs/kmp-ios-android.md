# Multiplatform (KMP)-iOS and Android Made Simple

## Introduction
When working with Kotlin Multiplatform (KMP), one of the most common challenges is handling platform-specific cryptography especially when encryption/decryption needs to be done inside shared code. Recently, I faced exactly this issue: I had to encrypt sensitive form data before sending it to an API.
The Android implementation was simple. But iOS not so much.
Here’s how I solved it — **cleanly, securely, and without leaking any platform secrets**.

### The Problem
I needed to encrypt sensitive fields like: Passwords, Device IDs, IP addresses and more **before making API calls** from the shared KMP code.

### Challenge:
- Android allowed a straightforward AES implementation using Java/Kotlin.
- But iOS doesn’t allow direct access to native encryption (like Keychain) from Kotlin shared code.
- I didn’t want to shift encryption logic completely to Swift.
- I wanted a **platform-specific implementation** that still **feels native inside the KMP layer**.

## The Shared Code: `AesEncryptionService`
To keep platform logic clean, I used the `expect/actual` pattern in Kotlin:
```
expect class AesEncryptionService() {
    fun encrypt(data: String): String
    fun decrypt(encryptedData: String): String
}
```
This allows me to define **different implementations for Android and iOS** while keeping usage consistent in shared code.

### Android Implementation
(Android devs can plug in their own AES logic here.)
```
actual class AesEncryptionService actual constructor() {
    actual fun encrypt(data: String): String {
        // Use javax.crypto or your favorite Android library
        return encryptWithAes(data)
    }
actual fun decrypt(encryptedData: String): String {
        return decryptWithAes(encryptedData)
    }
}
```

### iOS Implementation Using Callback Injection
Here’s where the magic happens:

#### Step 1: Define a Runtime Callback Handler
```
object EncryptionHandler {
    var encryptionCallback: ((String) -> String)? = null
    var decryptionCallback: ((String) -> String)? = null
fun encrypt(callback: (String) -> String) {
        encryptionCallback = callback
    }
    fun decrypt(callback: (String) -> String) {
        decryptionCallback = callback
    }
}
```
#### Step 2: Implement `actual` Class for iOS
```
actual class AesEncryptionService actual constructor() {
    actual fun encrypt(data: String): String {
        return EncryptionHandler.encryptionCallback?.invoke(data).orEmpty()
    }
actual fun decrypt(encryptedData: String): String {
        return EncryptionHandler.decryptionCallback?.invoke(encryptedData).orEmpty()
    }
}
```
This allows the encryption logic to be injected at runtime from Swift, without writing cryptographic logic directly in Kotlin.

##### Swift-side Usage (iOS)
Here’s how you inject native iOS encryption/decryption logic:
```
EncryptionHandler().encrypt { data in
    // iOS AES encryption logic
    return data.encryptString
}
EncryptionHandler().decrypt { encryptedData in
//iOS decryption logic NSString is my class in
//swift responsible for encryption/decryption
    return NSString.decryptData((encryptedData as! String), withKey: Services.mobOil)
}
```
Then, you can safely call your KMP-based API:
```
Task {
    let response = try await RepoHelper().repo.loginAPi(
        username: "username",
        password: "password"
    )
    print("data is \(response)") // ← Decrypted result!
}
```

## How It Works (Behind the Scenes)
Let’s break down **what actually happens** when you call `encrypt()` or `decrypt()` from your shared KMP module:

In shared code, you call: `AesEncryptionService().encrypt("someData")`

#### Android:
- The call is routed to the Android `actual` implementation.
- AES encryption happens using the Java/Kotlin crypto libraries.
- The encrypted string is returned and used in the API call.

#### iOS:
- The call is routed to the iOS `actual` implementation.
- That implementation doesn’t perform encryption itself — instead, it **calls the Swift callback you previously injected** via `EncryptionHandler.encrypt { }`.
- Swift performs native iOS encryption using your own logic (e.g., `CommonCrypto`, `Security`, or a Swift helper).
- The encrypted string is returned back to KMP shared code.
- The KMP module continues using the result in API logic.

##### Think of it like this:
> From the shared module’s point of view, encryption is just a black box.
> Android uses local logic, iOS delegates it to Swift — but **both give the same result**: an encrypted string ready for secure transmission.

Encryption happens natively
KMP remains clean
No confidential API or logic is shared across platforms

## Benefits
- No confidential API code in KMP
- Clean separation of platform-specific logic
- Native performance and security on both Android and iOS
- Future-proof: easily replace encryption logic or move to a more secure backend implementation
- Reusable pattern for other platform-dependent tasks like logging, analytics, feature flags

## Conclusion
Handling encryption across platforms in KMP doesn’t have to be messy.

By combining the `expect/actual` pattern with runtime callback injection, I was able to keep my code clean, testable, and secure on both Android and iOS—**without duplicating business logic** or exposing sensitive code.

This pattern isn’t just limited to encryption. You can apply it to analytics, logging, native dialogs, or even theming if needed.

If you’re dealing with similar cross-platform issues in KMP, give this method a try.

It saved me hours of debugging and just works.

##### [Source](https://medium.com/@mubashirmurtaza86/cross-platform-encryption-in-kotlin-multiplatform-kmp-ios-and-android-made-simple-0d27299114b7)
