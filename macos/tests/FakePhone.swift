import CryptoKit
import Foundation
import Network

/// 阻塞式的单行 TCP 客户端，供测试线程按顺序读写。
final class TestConnection {
    private let queue = DispatchQueue(label: "test.fake-phone.connection")
    private let channel: LineChannel
    private let lines = Inbox<Data>()
    private let closed = Inbox<String>()
    private var readIndex = 0

    init(port: UInt16, host: NWEndpoint.Host = "127.0.0.1") {
        let connection = NWConnection(host: host, port: NWEndpoint.Port(rawValue: port) ?? .any, using: .tcp)
        channel = LineChannel(connection: connection, queue: queue)
        channel.onLine = { [lines] in lines.put($0) }
        channel.onClose = { [closed] in closed.put($0) }
        channel.start()
    }

    func send(_ line: Data) {
        queue.async { [channel] in channel.send(line) }
    }

    func sendJSON<T: Encodable>(_ value: T) {
        if let line = Wire.encode(value) { send(line) }
    }

    /// 直接写原始字节（不加换行），用于超长帧测试。
    func sendRaw(_ bytes: Data) {
        queue.async { [channel] in
            channel.connection.send(content: bytes, completion: .contentProcessed { _ in })
        }
    }

    func nextLine(timeout: TimeInterval = 3) -> Data? {
        guard let line = lines.item(at: readIndex, timeout: timeout) else { return nil }
        readIndex += 1
        return line
    }

    func waitClosed(timeout: TimeInterval = 3) -> Bool {
        closed.item(at: 0, timeout: timeout) != nil
    }

    var isClosed: Bool { closed.count > 0 }

    func close() {
        queue.async { [channel] in channel.close("测试关闭") }
        _ = waitClosed(timeout: 1)
    }
}

/// 握手完成后的手机端会话（手机方向：发送用 p2m，接收用 m2p）。
final class FakeSession {
    let connection: TestConnection
    let cipher: FrameCipher
    let macId: String
    let macPublicKey: Data
    let sas: String
    let phoneKnown: Bool
    private(set) var macKnown: Bool?

    init(connection: TestConnection, cipher: FrameCipher, macId: String, macPublicKey: Data, sas: String, phoneKnown: Bool) {
        self.connection = connection
        self.cipher = cipher
        self.macId = macId
        self.macPublicKey = macPublicKey
        self.sas = sas
        self.phoneKnown = phoneKnown
    }

    func send<T: Encodable>(_ message: T) {
        guard let plain = Wire.encode(message) else { return }
        sendPlaintext(plain)
    }

    func sendObject(_ object: [String: Any]) {
        guard let plain = try? JSONSerialization.data(withJSONObject: object) else { return }
        sendPlaintext(plain)
    }

    func sendPlaintext(_ plain: Data) {
        guard let frame = try? cipher.seal(plain) else { return }
        connection.sendJSON(frame)
    }

    /// 读下一条加密帧并解密；连接关闭、超时或解密失败返回 nil。
    func receive(timeout: TimeInterval = 3) -> (type: String, plain: Data)? {
        guard let line = connection.nextLine(timeout: timeout),
              let frame = Wire.decode(EncFrame.self, from: line), frame.type == "enc",
              let plain = try? cipher.open(frame),
              let type = Wire.decode(TypeProbe.self, from: plain)?.type
        else { return nil }
        return (type, plain)
    }

    /// 交换 trust，返回 Mac 的 known。
    func exchangeTrust() -> Bool? {
        send(TrustMessage(known: phoneKnown))
        guard let reply = receive(), reply.type == "trust",
              let known = Wire.decode(TrustMessage.self, from: reply.plain)?.known else { return nil }
        macKnown = known
        return known
    }
}

/// 按 docs/desktop-protocol.md 实现手机端协议的假手机。
final class FakePhone {
    enum Tamper { case none, badCommit }

    let name: String
    let id: String
    let key: P256.KeyAgreement.PrivateKey
    private(set) var trustedMacs: [String: Data] = [:]

    init(name: String, key: P256.KeyAgreement.PrivateKey = P256.KeyAgreement.PrivateKey()) {
        self.name = name
        self.id = DesktopCrypto.randomBytes(16).hexString
        self.key = key
    }

    var publicKey: Data { key.publicKey.x963Representation }

    func forgetMacs() { trustedMacs = [:] }

    /// hello / hello / reveal，并建立加密会话。Mac 中途断开或回复无效时返回 nil。
    func handshake(port: UInt16, tamper: Tamper = .none, host: NWEndpoint.Host = "127.0.0.1") -> FakeSession? {
        let conn = TestConnection(port: port, host: host)
        let nonce = DesktopCrypto.randomBytes(16)
        let r = DesktopCrypto.randomBytes(16)
        let commit = DesktopCrypto.commit(phonePublicKey: publicKey, reveal: r)
        conn.sendJSON(HelloFrame(type: "hello", proto: 1, id: id, name: name, pub: publicKey.b64,
                                 nonce: nonce.b64, commit: commit.b64, pairNonce: nil))
        guard let line = conn.nextLine(),
              let hello = Wire.decode(HelloFrame.self, from: line), hello.type == "hello", hello.proto == 1,
              let macId = hello.id, PeerHello.isDeviceId(macId),
              let macPub = hello.pub.flatMap({ Data(base64Encoded: $0) }),
              let macKey = try? DesktopCrypto.publicKey(x963: macPub),
              let macNonce = hello.nonce.flatMap({ Data(base64Encoded: $0) }), macNonce.count == 16,
              let pairNonce = hello.pairNonce.flatMap({ Data(base64Encoded: $0) }), pairNonce.count == 16,
              let shared = try? DesktopCrypto.sharedSecret(privateKey: key, peer: macKey)
        else { return nil }
        let revealed = tamper == .badCommit ? DesktopCrypto.randomBytes(16) : r
        conn.sendJSON(RevealFrame(type: "reveal", r: revealed.b64))
        let keys = DesktopCrypto.sessionKeys(shared: shared, phoneNonce: nonce, macNonce: macNonce)
        let sas = DesktopCrypto.sas(shared: shared, phonePublicKey: publicKey, macPublicKey: macPub,
                                    reveal: r, pairNonce: pairNonce)
        return FakeSession(connection: conn, cipher: FrameCipher(sendKey: keys.p2m, receiveKey: keys.m2p),
                           macId: macId, macPublicKey: macPub, sas: sas,
                           phoneKnown: trustedMacs[macId] == macPub)
    }

    /// 手机用户确认配对码：保存 Mac、发送 paired，等 Mac 的 paired。
    func confirmPairing(_ session: FakeSession) -> Bool {
        trustedMacs[session.macId] = session.macPublicKey
        session.send(ControlMessage.paired)
        while let reply = session.receive() {
            if reply.type == "paired" { return true }
            if reply.type == "rejected" { return false }
        }
        return false
    }

    /// 完整连接：握手 + trust；需要配对时自动确认。返回进入数据阶段的会话。
    func connect(port: UInt16, host: NWEndpoint.Host = "127.0.0.1") -> FakeSession? {
        guard let session = handshake(port: port, host: host), let macKnown = session.exchangeTrust() else { return nil }
        if macKnown && session.phoneKnown { return session }
        return confirmPairing(session) ? session : nil
    }

    static func bg(mmol: Double, ts: Double?, trend: String = "FLAT", connected: Bool = true) -> [String: Any] {
        ["type": "bg", "value": String(format: "%.1f", mmol), "unit": "mmol/L", "mmol": mmol,
         "time": "16:33", "ts": ts.map { $0 as Any } ?? NSNull(), "trend": trend, "delta": 0.1,
         "connected": connected]
    }

    static func history(ref: Double, points: [[Int]], range: [Double] = [3.9, 7.8]) -> [String: Any] {
        ["type": "history", "ref": ref, "range": range, "points": points]
    }
}
