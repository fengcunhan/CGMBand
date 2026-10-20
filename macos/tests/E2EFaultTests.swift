import Foundation

/// 各种异常输入都必须断开（或安全忽略），且不产生血糖事件。
func runFaultTests(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    tamperedCiphertext(t, h, phone)
    wrongSequence(t, h, phone)
    badCommit(t, h, phone)
    unsupportedProto(t, h)
    malformedFrames(t, h, phone)
    newConnectionReplacesOld(t, h, phone)
    pairingDeclined(t, h)
}

private func connected(_ h: LinkHarness, _ phone: FakePhone) -> FakeSession? {
    let start = h.mark
    guard let s = phone.connect(port: h.port), h.waitConnected(phone, from: start) else { return nil }
    return s
}

private func tamperedCiphertext(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("篡改 ct → 断开")
    guard let s = connected(h, phone) else { return t.check(false, "连上") }
    let start = h.mark
    guard let plain = try? JSONSerialization.data(withJSONObject: FakePhone.bg(mmol: 9.9, ts: sampleTs)),
          let frame = try? s.cipher.seal(plain), var sealed = Data(base64Encoded: frame.ct)
    else { return t.check(false, "构造帧") }
    sealed[sealed.count / 2] ^= 0x01
    s.connection.sendJSON(EncFrame(seq: frame.seq, ct: sealed.b64))
    t.check(s.connection.waitClosed(), "Mac 断开连接")
    t.equal(h.readingCount(from: start), 0, "没有产生 bg")
    _ = h.waitState(from: start) { $0 == .listening }
}

private func wrongSequence(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("seq 跳号 / 重放 → 断开")
    guard let s = connected(h, phone) else { return t.check(false, "连上") }
    var start = h.mark
    guard let plain = try? JSONSerialization.data(withJSONObject: FakePhone.bg(mmol: 9.9, ts: sampleTs)) else { return }
    _ = try? s.cipher.seal(plain)                       // 跳过一个 seq
    guard let skipped = try? s.cipher.seal(plain) else { return t.check(false, "构造帧") }
    s.connection.sendJSON(skipped)
    t.check(s.connection.waitClosed(), "seq 跳号 → 断开")
    t.equal(h.readingCount(from: start), 0, "没有产生 bg")
    _ = h.waitState(from: start) { $0 == .listening }

    guard let r = connected(h, phone) else { return t.check(false, "重新连上") }
    start = h.mark
    guard let first = try? r.cipher.seal(plain) else { return }
    r.connection.sendJSON(first)
    r.connection.sendJSON(first)
    t.check(r.connection.waitClosed(), "重放同一 seq → 断开")
    t.equal(h.readingCount(from: start), 1, "只接受第一帧")
    _ = h.waitState(from: start) { $0 == .listening }
}

private func badCommit(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("reveal 与 commit 不符 → 断开")
    guard let s = phone.handshake(port: h.port, tamper: .badCommit) else { return t.check(false, "收到 Mac hello") }
    t.check(s.exchangeTrust() == nil, "Mac 不发送 trust")
    t.check(s.connection.waitClosed(), "Mac 断开连接")
}

private func unsupportedProto(_ t: TestRun, _ h: LinkHarness) {
    t.section("不支持的 proto → error 并断开")
    let conn = TestConnection(port: h.port)
    conn.send(Data(#"{"type":"hello","proto":2,"id":"x","name":"未来手机"}"#.utf8))
    let reply = conn.nextLine().flatMap { Wire.decode(ErrorFrame.self, from: $0) }
    t.check(reply?.type == "error" && reply?.code == "proto", "回复 {\"type\":\"error\",\"code\":\"proto\"}")
    t.check(conn.waitClosed(), "随后断开")
}

private func malformedFrames(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("畸形输入")
    let garbage = TestConnection(port: h.port)
    garbage.send(Data("{not json".utf8))
    t.check(garbage.waitClosed(), "握手阶段非 JSON → 断开")

    let badKey = TestConnection(port: h.port)
    badKey.send(Data(#"{"type":"hello","proto":1,"id":"00112233445566778899aabbccddeeff","name":"x","pub":"AAAA","nonce":"AAAA","commit":"AAAA"}"#.utf8))
    t.check(badKey.waitClosed(), "hello 公钥/nonce 无效 → 断开")

    let huge = TestConnection(port: h.port)
    huge.sendRaw(Data(repeating: 0x41, count: 70_000))
    t.check(huge.waitClosed(), "超过 64 KB 的帧 → 断开")

    guard let s = connected(h, phone) else { return t.check(false, "连上") }
    let start = h.mark
    s.sendPlaintext(Data("{not json".utf8))
    s.sendObject(["type": "bg", "mmol": "六点六"])
    s.sendObject(["type": "history", "points": "none"])
    s.send(ControlMessage.ping)
    t.equal(s.receive()?.type, "pong", "加密的畸形内层消息被忽略，连接保持")
    t.equal(h.readingCount(from: start), 0, "畸形 bg 不产生读数")
    s.connection.sendJSON(["type": "hello"])
    t.check(s.connection.waitClosed(), "数据阶段收到明文帧 → 断开")
    _ = h.waitState(from: start) { $0 == .listening }
}

private func newConnectionReplacesOld(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("新连接替换旧连接")
    guard let s = connected(h, phone) else { return t.check(false, "连上") }
    let start = h.mark
    let probe = TestConnection(port: h.port)
    t.check(!s.connection.waitClosed(timeout: 0.5), "只连接不发 hello 的新连接不会挤掉当前手机")
    s.send(ControlMessage.ping)
    t.equal(s.receive()?.type, "pong", "当前手机仍然可用")
    probe.close()

    let other = FakePhone(name: "Pixel 8")
    guard let o = other.handshake(port: h.port) else { return t.check(false, "另一台手机握手") }
    t.check(s.connection.waitClosed(), "新手机发出有效 hello 后旧连接被断开")
    t.check(!o.connection.isClosed, "新连接保留")
    o.connection.close()
    _ = h.waitState(from: start) { $0 == .listening }
}

private func pairingDeclined(_ t: TestRun, _ h: LinkHarness) {
    t.section("拒绝配对")
    h.acceptPairing.set(false)
    let stranger = FakePhone(name: "陌生手机")
    if let s = stranger.handshake(port: h.port), s.exchangeTrust() == false {
        t.equal(s.receive()?.type, "rejected", "Mac 用户拒绝 → 发送 rejected")
        t.check(s.connection.waitClosed(), "随后断开")
    } else {
        t.check(false, "陌生手机握手")
    }
    t.check(!h.trust.isTrusted(id: stranger.id, publicKey: stranger.publicKey), "未保存陌生手机")
    h.acceptPairing.set(true)

    let fickle = FakePhone(name: "反悔的手机")
    guard let s = fickle.handshake(port: h.port), s.exchangeTrust() == false else { return t.check(false, "握手") }
    t.equal(s.receive()?.type, "paired", "Mac 用户确认 → 发送 paired")
    s.send(ControlMessage.rejected)
    t.check(s.connection.waitClosed(), "手机拒绝 → Mac 断开")
    t.check(h.waitUntil { !h.trust.isTrusted(id: fickle.id, publicKey: fickle.publicKey) },
            "手机拒绝后撤销本次保存的信任")
}
