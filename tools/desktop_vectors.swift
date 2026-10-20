// Test vectors for the phone ↔ Mac desktop protocol (docs/desktop-protocol.md).
// Run: swift tools/desktop_vectors.swift
// Both implementations (Kotlin javax.crypto, Swift CryptoKit) must reproduce these exact values.
import CryptoKit
import Foundation

func bytes(_ b: UInt8, _ n: Int) -> Data { Data(repeating: b, count: n) }
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func b64(_ d: Data) -> String { d.base64EncodedString() }
func utf8(_ s: String) -> Data { Data(s.utf8) }

let phoneKey = try! P256.KeyAgreement.PrivateKey(rawRepresentation: bytes(0x11, 32))
let macKey = try! P256.KeyAgreement.PrivateKey(rawRepresentation: bytes(0x22, 32))
let phonePub = phoneKey.publicKey.x963Representation   // 65 bytes, 0x04 || X || Y
let macPub = macKey.publicKey.x963Representation

let phoneNonce = bytes(0xA0, 16)   // hello.nonce (phone)
let macNonce = bytes(0xB0, 16)     // hello.nonce (mac)
let phoneReveal = bytes(0xC0, 16)  // reveal.r (phone), committed in hello.commit
let macPairNonce = bytes(0xD0, 16) // hello.pairNonce (mac)

let commit = Data(SHA256.hash(data: utf8("cgmband/v1/commit") + phonePub + phoneReveal))

let shared = try! phoneKey.sharedSecretFromKeyAgreement(with: macKey.publicKey)
let sharedRaw = shared.withUnsafeBytes { Data($0) }

func hkdf(_ salt: Data, _ info: String, _ length: Int) -> Data {
    shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: utf8(info), outputByteCount: length)
        .withUnsafeBytes { Data($0) }
}

let sessionSalt = phoneNonce + macNonce
let p2m = hkdf(sessionSalt, "cgmband/v1/p2m", 32)
let m2p = hkdf(sessionSalt, "cgmband/v1/m2p", 32)

let sasSalt = Data(SHA256.hash(data: phonePub + macPub + phoneReveal + macPairNonce))
let sasBytes = hkdf(sasSalt, "cgmband/v1/sas", 4)
let sas = String(format: "%06u", sasBytes.reduce(UInt32(0)) { $0 << 8 | UInt32($1) } % 1_000_000)

func gcmNonce(_ seq: UInt64) -> AES.GCM.Nonce {
    var n = Data(count: 4)
    withUnsafeBytes(of: seq.bigEndian) { n.append(contentsOf: $0) }
    return try! AES.GCM.Nonce(data: n)
}

func seal(_ key: Data, _ seq: UInt64, _ text: String) -> String {
    let box = try! AES.GCM.seal(utf8(text), using: SymmetricKey(data: key), nonce: gcmNonce(seq), authenticating: utf8("cgmband/v1"))
    return b64(box.ciphertext + box.tag)
}

print("phonePub=\(b64(phonePub))")
print("macPub=\(b64(macPub))")
print("commit=\(b64(commit))")
print("shared=\(hex(sharedRaw))")
print("p2m=\(hex(p2m))")
print("m2p=\(hex(m2p))")
print("sas=\(sas)")
print("ct_p2m_seq0=\(seal(p2m, 0, "{\"type\":\"ping\"}"))")
print("ct_m2p_seq5=\(seal(m2p, 5, "{\"type\":\"sync\"}"))")
