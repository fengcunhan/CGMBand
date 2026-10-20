import CryptoKit
import Foundation

/// 手机 → Mac 桌面端协议（docs/desktop-protocol.md）的密码学原语。全部是纯函数，便于用测试向量校验。
enum DesktopCrypto {
    static let aad = Data("cgmband/v1".utf8)
    static let commitLabel = Data("cgmband/v1/commit".utf8)
    static let p2mInfo = "cgmband/v1/p2m"
    static let m2pInfo = "cgmband/v1/m2p"
    static let sasInfo = "cgmband/v1/sas"
    static let tagLength = 16

    struct SessionKeys: Equatable {
        let p2m: Data   // 手机 → Mac
        let m2p: Data   // Mac → 手机
    }

    enum Failure: Error {
        case badPublicKey
        case badCiphertext
    }

    /// 密码学安全的随机字节（CryptoKit 内部使用系统 CSPRNG）。
    static func randomBytes(_ count: Int) -> Data {
        SymmetricKey(size: SymmetricKeySize(bitCount: count * 8)).withUnsafeBytes { Data($0) }
    }

    static func publicKey(x963 data: Data) throws -> P256.KeyAgreement.PublicKey {
        guard data.count == 65, data.first == 0x04 else { throw Failure.badPublicKey }
        do {
            return try P256.KeyAgreement.PublicKey(x963Representation: data)
        } catch {
            throw Failure.badPublicKey
        }
    }

    /// commit = SHA-256("cgmband/v1/commit" || 手机公钥 || r)
    static func commit(phonePublicKey: Data, reveal: Data) -> Data {
        Data(SHA256.hash(data: commitLabel + phonePublicKey + reveal))
    }

    /// ECDH 共享密钥：32 字节 X 坐标。
    static func sharedSecret(privateKey: P256.KeyAgreement.PrivateKey,
                             peer: P256.KeyAgreement.PublicKey) throws -> Data {
        try privateKey.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }

    static func hkdf(ikm: Data, salt: Data, info: String, count: Int) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                               info: Data(info.utf8), outputByteCount: count)
            .withUnsafeBytes { Data($0) }
    }

    static func sessionKeys(shared: Data, phoneNonce: Data, macNonce: Data) -> SessionKeys {
        let salt = phoneNonce + macNonce
        return SessionKeys(p2m: hkdf(ikm: shared, salt: salt, info: p2mInfo, count: 32),
                           m2p: hkdf(ikm: shared, salt: salt, info: m2pInfo, count: 32))
    }

    /// 6 位配对码：HKDF 4 字节 → 大端无符号整数 → mod 1 000 000 → 补零。
    static func sas(shared: Data, phonePublicKey: Data, macPublicKey: Data,
                    reveal: Data, pairNonce: Data) -> String {
        let salt = Data(SHA256.hash(data: phonePublicKey + macPublicKey + reveal + pairNonce))
        let bytes = hkdf(ikm: shared, salt: salt, info: sasInfo, count: 4)
        let value = bytes.reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return String(format: "%06u", value % 1_000_000)
    }

    /// 12 字节 nonce = 4 个 0x00 + seq 的 8 字节大端。
    static func nonce(seq: UInt64) -> AES.GCM.Nonce {
        var bytes = Data(count: 4)
        withUnsafeBytes(of: seq.bigEndian) { bytes.append(contentsOf: $0) }
        // 12 字节长度固定，构造不会失败；万一失败退回随机 nonce 会导致对端解密失败而断开，不会泄密。
        return (try? AES.GCM.Nonce(data: bytes)) ?? AES.GCM.Nonce()
    }

    /// 返回 密文 || 16 字节 tag。
    static func seal(_ plaintext: Data, key: Data, seq: UInt64) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                   nonce: nonce(seq: seq), authenticating: aad)
        return box.ciphertext + box.tag
    }

    static func open(_ sealed: Data, key: Data, seq: UInt64) throws -> Data {
        guard sealed.count >= tagLength else { throw Failure.badCiphertext }
        let split = sealed.count - tagLength
        let box = try AES.GCM.SealedBox(nonce: nonce(seq: seq),
                                        ciphertext: sealed.prefix(split),
                                        tag: sealed.suffix(tagLength))
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}

/// 一条连接上某一方向的加解密状态：严格递增的 seq，从 0 开始。
final class FrameCipher {
    enum Failure: Error, CustomStringConvertible {
        case sequence(expected: UInt64, got: UInt64)
        case malformed
        case authentication

        var description: String {
            switch self {
            case let .sequence(expected, got): return "seq 不符（期望 \(expected)，收到 \(got)）"
            case .malformed: return "密文格式错误"
            case .authentication: return "密文校验失败"
            }
        }
    }

    private let sendKey: Data
    private let receiveKey: Data
    private(set) var nextSendSeq: UInt64 = 0
    private(set) var nextReceiveSeq: UInt64 = 0

    init(sendKey: Data, receiveKey: Data) {
        self.sendKey = sendKey
        self.receiveKey = receiveKey
    }

    func seal(_ plaintext: Data) throws -> EncFrame {
        let sealed = try DesktopCrypto.seal(plaintext, key: sendKey, seq: nextSendSeq)
        let frame = EncFrame(seq: nextSendSeq, ct: sealed.base64EncodedString())
        nextSendSeq += 1
        return frame
    }

    func open(_ frame: EncFrame) throws -> Data {
        guard frame.seq == nextReceiveSeq else {
            throw Failure.sequence(expected: nextReceiveSeq, got: frame.seq)
        }
        guard let sealed = Data(base64Encoded: frame.ct) else { throw Failure.malformed }
        do {
            let plain = try DesktopCrypto.open(sealed, key: receiveKey, seq: frame.seq)
            nextReceiveSeq += 1
            return plain
        } catch {
            throw Failure.authentication
        }
    }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
