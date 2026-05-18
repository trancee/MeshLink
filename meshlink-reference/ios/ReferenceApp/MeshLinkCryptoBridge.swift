import CryptoKit
import Foundation
import ReferenceAppShared
import Security

enum MeshLinkReferenceCryptoBridge {
    static func install() {
        IosCryptoBridge.shared.install(
            randomBytes: { size in
                randomData(count: Int(truncating: size)).toKotlinByteArray()
            },
            sha256: { input in
                Data(SHA256.hash(data: input.toData())).toKotlinByteArray()
            },
            hmacSha256: { key, data in
                let authenticationCode = HMAC<SHA256>.authenticationCode(
                    for: data.toData(),
                    using: SymmetricKey(data: key.toData())
                )
                return Data(authenticationCode).toKotlinByteArray()
            },
            generateX25519KeyPair: {
                let privateKey = Curve25519.KeyAgreement.PrivateKey()
                return IosCryptoRawKeyPair(
                    privateKey: privateKey.rawRepresentation.toKotlinByteArray(),
                    publicKey: privateKey.publicKey.rawRepresentation.toKotlinByteArray()
                )
            },
            generateEd25519KeyPair: {
                let privateKey = Curve25519.Signing.PrivateKey()
                return IosCryptoRawKeyPair(
                    privateKey: privateKey.rawRepresentation.toKotlinByteArray(),
                    publicKey: privateKey.publicKey.rawRepresentation.toKotlinByteArray()
                )
            },
            x25519: { privateKeyBytes, publicKeyBytes in
                do {
                    let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyBytes.toData())
                    let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKeyBytes.toData())
                    let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
                    return sharedSecret.withUnsafeBytes { Data($0).toKotlinByteArray() }
                } catch {
                    fatalError("MeshLinkReferenceCryptoBridge X25519 failed: \(error)")
                }
            },
            ed25519Sign: { privateKeyBytes, message in
                do {
                    let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: privateKeyBytes.toData())
                    return try privateKey.signature(for: message.toData()).toKotlinByteArray()
                } catch {
                    fatalError("MeshLinkReferenceCryptoBridge Ed25519 sign failed: \(error)")
                }
            },
            ed25519Verify: { publicKeyBytes, message, signatureBytes in
                do {
                    let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicKeyBytes.toData())
                    let isValid = publicKey.isValidSignature(signatureBytes.toData(), for: message.toData())
                    return KotlinBoolean(bool: isValid)
                } catch {
                    return KotlinBoolean(bool: false)
                }
            },
            chacha20Poly1305Seal: { keyBytes, nonceBytes, aadBytes, plaintext in
                do {
                    let key = SymmetricKey(data: keyBytes.toData())
                    let nonce = try ChaChaPoly.Nonce(data: nonceBytes.toData())
                    let sealedBox = try ChaChaPoly.seal(
                        plaintext.toData(),
                        using: key,
                        nonce: nonce,
                        authenticating: aadBytes.toData()
                    )
                    var combinedCiphertext = Data()
                    combinedCiphertext.append(sealedBox.ciphertext)
                    combinedCiphertext.append(sealedBox.tag)
                    return combinedCiphertext.toKotlinByteArray()
                } catch {
                    fatalError("MeshLinkReferenceCryptoBridge ChaCha20-Poly1305 seal failed: \(error)")
                }
            },
            chacha20Poly1305Open: { keyBytes, nonceBytes, aadBytes, ciphertextAndTag in
                do {
                    let ciphertextData = ciphertextAndTag.toData()
                    let tagSize = 16
                    guard ciphertextData.count >= tagSize else {
                        fatalError("MeshLinkReferenceCryptoBridge ChaCha20-Poly1305 ciphertext is shorter than the authentication tag")
                    }
                    let key = SymmetricKey(data: keyBytes.toData())
                    let nonce = try ChaChaPoly.Nonce(data: nonceBytes.toData())
                    let ciphertext = ciphertextData.prefix(ciphertextData.count - tagSize)
                    let tag = ciphertextData.suffix(tagSize)
                    let sealedBox = try ChaChaPoly.SealedBox(
                        nonce: nonce,
                        ciphertext: ciphertext,
                        tag: tag
                    )
                    let plaintext = try ChaChaPoly.open(
                        sealedBox,
                        using: key,
                        authenticating: aadBytes.toData()
                    )
                    return plaintext.toKotlinByteArray()
                } catch {
                    fatalError("MeshLinkReferenceCryptoBridge ChaCha20-Poly1305 open failed: \(error)")
                }
            }
        )
    }

    private static func randomData(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed with status \(status)")
        return Data(bytes)
    }
}

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let kotlinBytes = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            kotlinBytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return kotlinBytes
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var bytes = [UInt8](repeating: 0, count: Int(size))
        for index in 0..<Int(size) {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }
}
