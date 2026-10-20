import CryptoKit
import Foundation

enum DesktopProtocol {
    static let version = 1
    static let serviceType = "_cgmband._tcp"
    /// 固定监听端口：手机缓存的 Bonjour 解析结果在 Mac 重启后仍然有效；被占用时退回系统分配的端口。
    static let defaultPort: UInt16 = 47812
    /// 单帧（一行 JSON，不含结尾 \n）的最大字节数。
    static let maxFrameBytes = 64 * 1024
    static let nonceLength = 16
    static let commitLength = 32
    static let maxNameLength = 64
}

/// 一行 JSON 的编解码。解码失败一律返回 nil，由调用方决定断开还是忽略。
enum Wire {
    static func encode<T: Encodable>(_ value: T) -> Data? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return try? encoder.encode(value)
    }

    static func decode<T: Decodable>(_ type: T.Type, from data: Data) -> T? {
        try? JSONDecoder().decode(type, from: data)
    }
}

// MARK: - 明文握手帧

struct HelloProbe: Decodable {
    let type: String?
    let proto: Int?
}

struct HelloFrame: Codable {
    let type: String
    let proto: Int?
    let id: String?
    let name: String?
    let pub: String?
    let nonce: String?
    let commit: String?      // 仅手机发送
    let pairNonce: String?   // 仅 Mac 发送

    static func mac(id: String, name: String, publicKey: Data, nonce: Data, pairNonce: Data) -> HelloFrame {
        HelloFrame(type: "hello", proto: DesktopProtocol.version, id: id, name: name,
                   pub: publicKey.base64EncodedString(), nonce: nonce.base64EncodedString(),
                   commit: nil, pairNonce: pairNonce.base64EncodedString())
    }
}

struct RevealFrame: Codable {
    let type: String
    let r: String?
}

struct ErrorFrame: Codable {
    let type: String
    let code: String

    static let unsupportedProto = ErrorFrame(type: "error", code: "proto")
}

/// 加密帧：{"type":"enc","seq":0,"ct":"<base64(密文 || tag)>"}
struct EncFrame: Codable, Equatable {
    let type: String
    let seq: UInt64
    let ct: String

    init(seq: UInt64, ct: String) {
        self.type = "enc"
        self.seq = seq
        self.ct = ct
    }
}

// MARK: - 内层消息

struct TypeProbe: Decodable {
    let type: String?
}

/// 只有 type 的控制消息：ping / pong / paired / rejected / sync。
struct ControlMessage: Codable {
    let type: String

    static let ping = ControlMessage(type: "ping")
    static let pong = ControlMessage(type: "pong")
    static let paired = ControlMessage(type: "paired")
    static let rejected = ControlMessage(type: "rejected")
    static let sync = ControlMessage(type: "sync")
}

struct TrustMessage: Codable {
    let type: String
    let known: Bool?

    init(known: Bool) {
        self.type = "trust"
        self.known = known
    }
}

// MARK: - 校验后的手机 hello

struct PeerHello {
    let id: String
    let name: String
    let publicKey: P256.KeyAgreement.PublicKey
    let publicKeyData: Data
    let nonce: Data
    let commit: Data

    /// 校验所有字段；任何一项不合规就返回 nil（调用方断开连接）。
    init?(_ frame: HelloFrame) {
        guard let id = frame.id, PeerHello.isDeviceId(id),
              let pubText = frame.pub, let pub = Data(base64Encoded: pubText),
              let key = try? DesktopCrypto.publicKey(x963: pub),
              let nonceText = frame.nonce, let nonce = Data(base64Encoded: nonceText),
              nonce.count == DesktopProtocol.nonceLength,
              let commitText = frame.commit, let commit = Data(base64Encoded: commitText),
              commit.count == DesktopProtocol.commitLength
        else { return nil }
        self.id = id
        self.name = PeerHello.sanitizedName(frame.name)
        self.publicKey = key
        self.publicKeyData = pub
        self.nonce = nonce
        self.commit = commit
    }

    /// 设备 ID：16 字节随机数的十六进制（32 个字符）。
    static func isDeviceId(_ s: String) -> Bool {
        s.utf8.count == 32 && s.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x41...0x46).contains($0) || (0x61...0x66).contains($0) }
    }

    /// 去掉控制字符、限制长度，避免显示到菜单和弹窗里出问题。
    static func sanitizedName(_ raw: String?) -> String {
        let cleaned = (raw ?? "").unicodeScalars
            .filter { !CharacterSet.controlCharacters.contains($0) }
            .map(String.init).joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let limited = String(cleaned.prefix(DesktopProtocol.maxNameLength))
        return limited.isEmpty ? "Android 手机" : limited
    }
}
