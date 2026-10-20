import CryptoKit
import Foundation

struct PhoneInfo: Equatable {
    let id: String
    let name: String
}

struct PhoneLinkTiming {
    var pingInterval: TimeInterval = 30       // 空闲 30 秒发一次 ping
    var silenceTimeout: TimeInterval = 75     // 75 秒没收到任何帧就断开
    var handshakeTimeout: TimeInterval = 30   // hello → trust 交换必须在此时间内完成
    var pairingTimeout: TimeInterval = 120    // 等双方确认配对码的最长时间
    var pairingPromptDelay: TimeInterval = 0.8 // 手机后台连接遇到配对会立即断开，稍等再弹窗避免闪现
    var tick: TimeInterval = 1
}

struct SessionEnvironment {
    let identity: DesktopIdentity
    let macName: String
    let trust: TrustStore
    let timing: PhoneLinkTiming
    let queue: DispatchQueue
}

enum SessionEvent {
    /// 收到有效 hello：PhoneLink 据此让这条连接取代当前连接。
    case identified(PhoneInfo)
    case pairingNeeded(PhoneInfo, sas: String)
    case connected(PhoneInfo)
    case reading(PhoneReading)
    case history(PhoneHistory)
    case log(String)
    /// cancelledPromptSAS：连接断开时仍在等用户确认的配对码（用于关闭对应弹窗）。
    case closed(reason: String, cancelledPromptSAS: String?)
}

/// 一条手机连接的服务端状态机：hello/hello/reveal → trust → （配对）→ 数据阶段。
/// 只在 `env.queue` 上使用。
final class PhoneSession {
    enum Phase { case hello, reveal, trust, pairing, data, closed }

    private struct Handshake {
        let peer: PeerHello
        let macNonce: Data
        let pairNonce: Data
    }

    var onEvent: ((SessionEvent) -> Void)?
    private(set) var phase: Phase = .hello
    private(set) var phone: PhoneInfo?

    private let channel: LineChannel
    private let env: SessionEnvironment
    private var timer: DispatchSourceTimer?
    private var handshake: Handshake?
    private var cipher: FrameCipher?
    private var sas = ""
    private var localKnown = false
    private var startedAt = Date()
    private var pairingStartedAt = Date()
    private var lastReceived = Date()
    private var lastSent = Date()
    private var promptShown = false
    private var localDecided = false
    private var localConfirmed = false
    private var remotePaired = false
    private var savedTrustThisSession = false

    init(channel: LineChannel, env: SessionEnvironment) {
        self.channel = channel
        self.env = env
    }

    func start() {
        startedAt = Date()
        lastReceived = startedAt
        channel.onLine = { [weak self] line in self?.receive(line) }
        channel.onClose = { [weak self] reason in self?.close(reason) }
        channel.start()
        let timer = DispatchSource.makeTimerSource(queue: env.queue)
        timer.schedule(deadline: .now() + env.timing.tick, repeating: env.timing.tick)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        self.timer = timer
    }

    /// 本机用户对配对码的决定（来自弹窗）。
    func resolvePairing(_ accepted: Bool) {
        guard phase == .pairing, !localDecided, let hs = handshake else { return }
        localDecided = true
        guard accepted else {
            return closeAfterSending(ControlMessage.rejected, reason: "已拒绝配对")
        }
        let wasTrusted = env.trust.isTrusted(id: hs.peer.id, publicKey: hs.peer.publicKeyData)
        env.trust.save(TrustedPhone(id: hs.peer.id, publicKey: hs.peer.publicKeyData, name: hs.peer.name))
        savedTrustThisSession = !wasTrusted
        localConfirmed = true
        sendSealed(ControlMessage.paired)
        finishPairingIfReady()
    }

    func close(_ reason: String) {
        closeSession(reason, finalLine: nil)
    }

    // MARK: - 收帧

    private func receive(_ line: Data) {
        lastReceived = Date()
        switch phase {
        case .hello: handleHello(line)
        case .reveal: handleReveal(line)
        case .trust, .pairing, .data: handleSealed(line)
        case .closed: break
        }
    }

    private func handleHello(_ line: Data) {
        guard let probe = Wire.decode(HelloProbe.self, from: line), probe.type == "hello" else {
            return close("握手失败：首帧不是 hello")
        }
        guard probe.proto == DesktopProtocol.version else {
            let line = Wire.encode(ErrorFrame.unsupportedProto)
            return closeSession("手机协议版本 \(probe.proto.map(String.init) ?? "?") 不受支持", finalLine: line)
        }
        guard let frame = Wire.decode(HelloFrame.self, from: line), let peer = PeerHello(frame) else {
            return close("握手失败：hello 字段无效")
        }
        let hs = Handshake(peer: peer, macNonce: DesktopCrypto.randomBytes(DesktopProtocol.nonceLength),
                           pairNonce: DesktopCrypto.randomBytes(DesktopProtocol.nonceLength))
        handshake = hs
        let info = PhoneInfo(id: peer.id, name: peer.name)
        phone = info
        onEvent?(.identified(info))
        let reply = HelloFrame.mac(id: env.identity.deviceId, name: env.macName,
                                   publicKey: env.identity.publicKey, nonce: hs.macNonce, pairNonce: hs.pairNonce)
        phase = .reveal
        sendPlain(reply)
    }

    private func handleReveal(_ line: Data) {
        guard let hs = handshake,
              let frame = Wire.decode(RevealFrame.self, from: line), frame.type == "reveal",
              let r = frame.r.flatMap({ Data(base64Encoded: $0) }), r.count == DesktopProtocol.nonceLength
        else { return close("握手失败：reveal 无效") }
        guard DesktopCrypto.commit(phonePublicKey: hs.peer.publicKeyData, reveal: r) == hs.peer.commit else {
            return close("握手失败：commit 校验不通过")
        }
        guard let shared = try? DesktopCrypto.sharedSecret(privateKey: env.identity.privateKey, peer: hs.peer.publicKey) else {
            return close("握手失败：密钥协商失败")
        }
        let keys = DesktopCrypto.sessionKeys(shared: shared, phoneNonce: hs.peer.nonce, macNonce: hs.macNonce)
        cipher = FrameCipher(sendKey: keys.m2p, receiveKey: keys.p2m)
        sas = DesktopCrypto.sas(shared: shared, phonePublicKey: hs.peer.publicKeyData,
                                macPublicKey: env.identity.publicKey, reveal: r, pairNonce: hs.pairNonce)
        localKnown = env.trust.isTrusted(id: hs.peer.id, publicKey: hs.peer.publicKeyData)
        phase = .trust
        sendSealed(TrustMessage(known: localKnown))
    }

    private func handleSealed(_ line: Data) {
        guard let cipher, let frame = Wire.decode(EncFrame.self, from: line), frame.type == "enc" else {
            return close("收到无效的加密帧")
        }
        let plain: Data
        do {
            plain = try cipher.open(frame)
        } catch {
            return close("解密失败：\(error)")
        }
        guard let type = Wire.decode(TypeProbe.self, from: plain)?.type else {
            return log("忽略无法解析的消息")
        }
        switch phase {
        case .trust: handleTrustPhase(type, plain)
        case .pairing: handlePairingPhase(type)
        case .data: handleDataPhase(type, plain)
        default: break
        }
    }

    private func handleTrustPhase(_ type: String, _ plain: Data) {
        switch type {
        case "trust":
            let remoteKnown = Wire.decode(TrustMessage.self, from: plain)?.known ?? false
            if localKnown && remoteKnown { enterDataPhase() } else { beginPairing(remoteKnown: remoteKnown) }
        case "ping": sendSealed(ControlMessage.pong)
        default: break
        }
    }

    private func handlePairingPhase(_ type: String) {
        switch type {
        case "paired":
            remotePaired = true
            finishPairingIfReady()
        case "rejected":
            if savedTrustThisSession, let id = phone?.id { env.trust.remove(id: id) }
            close("手机拒绝了配对")
        case "ping": sendSealed(ControlMessage.pong)
        default: break
        }
    }

    private func handleDataPhase(_ type: String, _ plain: Data) {
        switch type {
        case "bg":
            guard let reading = Wire.decode(PhoneReading.self, from: plain) else { return log("忽略格式错误的 bg") }
            onEvent?(.reading(reading))
        case "history":
            guard let history = Wire.decode(PhoneHistory.self, from: plain) else { return log("忽略格式错误的 history") }
            onEvent?(.history(history))
        case "ping":
            sendSealed(ControlMessage.pong)
        default:
            break   // pong、sync 以及未知 type 一律忽略
        }
    }

    // MARK: - 配对与数据阶段

    private func beginPairing(remoteKnown: Bool) {
        phase = .pairing
        pairingStartedAt = Date()
        log("需要配对（本机\(localKnown ? "已" : "未")信任，手机\(remoteKnown ? "已" : "未")信任）")
        env.queue.asyncAfter(deadline: .now() + env.timing.pairingPromptDelay) { [weak self] in
            guard let self, self.phase == .pairing, !self.promptShown, let phone = self.phone else { return }
            self.promptShown = true
            self.onEvent?(.pairingNeeded(phone, sas: self.sas))
        }
    }

    private func finishPairingIfReady() {
        guard localConfirmed, remotePaired else { return }
        log("配对完成")
        enterDataPhase()
    }

    private func enterDataPhase() {
        guard let hs = handshake, let phone else { return }
        env.trust.rename(id: hs.peer.id, publicKey: hs.peer.publicKeyData, to: hs.peer.name)
        phase = .data
        onEvent?(.connected(phone))
    }

    // MARK: - 定时器

    private func tick() {
        let now = Date()
        switch phase {
        case .hello, .reveal, .trust:
            if now.timeIntervalSince(startedAt) > env.timing.handshakeTimeout { close("握手超时") }
        case .pairing:
            if now.timeIntervalSince(pairingStartedAt) > env.timing.pairingTimeout { close("配对超时") }
        case .data:
            if now.timeIntervalSince(lastReceived) > env.timing.silenceTimeout {
                close("\(Int(env.timing.silenceTimeout)) 秒未收到手机消息")
            } else if now.timeIntervalSince(lastSent) >= env.timing.pingInterval {
                sendSealed(ControlMessage.ping)
            }
        case .closed:
            break
        }
    }

    // MARK: - 发送与关闭

    private func sendPlain<T: Encodable>(_ message: T) {
        guard let line = Wire.encode(message) else { return close("编码失败") }
        channel.send(line)
        lastSent = Date()
    }

    private func sealedLine<T: Encodable>(_ message: T) -> Data? {
        guard let cipher, let plain = Wire.encode(message), let frame = try? cipher.seal(plain) else { return nil }
        return Wire.encode(frame)
    }

    private func sendSealed<T: Encodable>(_ message: T) {
        guard let line = sealedLine(message) else { return close("加密失败") }
        channel.send(line)
        lastSent = Date()
    }

    private func closeAfterSending<T: Encodable>(_ message: T, reason: String) {
        closeSession(reason, finalLine: sealedLine(message))
    }

    private func closeSession(_ reason: String, finalLine: Data?) {
        guard phase != .closed else { return }
        let outstanding = phase == .pairing && promptShown && !localDecided
        phase = .closed
        timer?.cancel()
        timer = nil
        if let finalLine { channel.sendThenClose(finalLine, reason: reason) } else { channel.close(reason) }
        onEvent?(.closed(reason: reason, cancelledPromptSAS: outstanding ? sas : nil))
        onEvent = nil
    }

    private func log(_ message: String) {
        onEvent?(.log(message))
    }
}
