import Foundation

let sampleTs: Double = 1_792_398_000_000

func runE2ETests(_ t: TestRun) {
    t.section("端到端：PhoneLink（回环）+ 假手机")
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cgm-e2e-\(UUID().uuidString)")
    let suite = testDefaultsSuite
    defer { try? FileManager.default.removeItem(at: dir) }
    guard let identity = try? DesktopIdentity.loadOrCreate(in: dir),
          let defaults = freshTestDefaults()
    else { return t.check(false, "测试环境可以建立") }
    defer { defaults.removePersistentDomain(forName: suite) }
    let trust = TrustStore(defaults: defaults)

    var timing = PhoneLinkTiming()
    timing.pairingPromptDelay = 0.05
    timing.tick = 0.05
    timing.handshakeTimeout = 5
    timing.pairingTimeout = 5
    let h = LinkHarness(identity: identity, trust: trust, timing: timing)
    guard h.start() else { return t.check(false, "PhoneLink 在回环地址上启动") }
    t.check(true, "PhoneLink 在 127.0.0.1:\(h.port) 启动")

    let phone = FakePhone(name: "Xiaomi 13 Ultra")
    firstConnectionPairs(t, h, phone, macId: identity.deviceId)
    trustedReconnectSkipsPairing(t, h, phone)
    ipv6Works(t, h, phone)
    runFaultTests(t, h, phone)
    unpairRequiresPairingAgain(t, h, phone)
    h.stop()

    var fast = timing
    fast.pingInterval = 0.3
    fast.silenceTimeout = 1.0
    fast.handshakeTimeout = 0.8
    let h2 = LinkHarness(identity: identity, trust: trust, timing: fast)
    guard h2.start() else { return t.check(false, "第二个 PhoneLink 启动") }
    timeoutsDropConnection(t, h2, phone)
    h2.stop()
    preferredPortFallsBack(t, identity: identity, trust: trust, timing: timing)
}

private func ipv6Works(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("IPv6（::1）连接")
    let start = h.mark
    guard let s = phone.connect(port: h.port, host: "::1") else { return t.check(false, "经 IPv6 握手") }
    t.check(h.waitConnected(phone, from: start), "经 IPv6 进入数据阶段")
    s.sendObject(FakePhone.bg(mmol: 6.1, ts: sampleTs + 30_000))
    t.equal(h.waitReading(from: start)?.glucose, 6.1, "经 IPv6 收到 bg")
    t.check(h.disconnect(s), "断开")
}

private func preferredPortFallsBack(_ t: TestRun, identity: DesktopIdentity, trust: TrustStore, timing: PhoneLinkTiming) {
    t.section("固定端口与回退")
    let wanted = UInt16.random(in: 47_900...48_900)
    let first = LinkHarness(identity: identity, trust: trust, timing: timing, port: wanted)
    let second = LinkHarness(identity: identity, trust: trust, timing: timing, port: wanted)
    t.check(first.start() && first.port == wanted, "首选端口 \(wanted) 可用时使用它")

    // 进程内重启监听（旧的「重新监听」操作）：必须回到同一个端口，而不是系统分配的端口
    let phone = FakePhone(name: "Restart Phone")
    let start = first.mark
    let session = phone.connect(port: wanted)
    t.check(session != nil && first.waitConnected(phone, from: start), "重启前有手机连着")
    let mark = first.mark
    first.link.stop()
    first.link.start()
    let restartedPort = first.events.wait(from: mark, timeout: 5) { $0.listenerPort != nil }?.listenerPort
    t.equal(restartedPort, wanted, "stop() 后立刻 start()：仍监听首选端口 \(wanted)")
    t.check(session?.connection.waitClosed() ?? false, "重启会断开旧连接")
    t.check(phone.connect(port: wanted) != nil, "手机按原端口重连成功")

    t.check(second.start() && second.port != wanted, "首选端口被别的监听占用 → 重试后改用系统分配的端口（\(second.port)）")
    second.stop()
    first.stop()
}

private func firstConnectionPairs(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone, macId: String) {
    t.section("首次连接 → 配对 → 数据")
    let start = h.mark
    let promptsBefore = h.prompts.count
    guard let s = phone.handshake(port: h.port) else { return t.check(false, "握手（hello/hello/reveal）") }
    t.equal(s.macId, macId, "Mac hello 带设备 ID")
    t.equal(s.exchangeTrust(), false, "Mac 不认识新手机 → trust.known=false")
    guard let prompt = h.prompts.item(at: promptsBefore) else { return t.check(false, "Mac 请求用户确认配对") }
    t.equal(prompt.name, "Xiaomi 13 Ultra", "配对弹窗显示手机名")
    t.equal(prompt.sas, s.sas, "两端配对码一致（\(s.sas)）")
    t.check(h.waitState(from: start) { $0 == .pairing(PhoneInfo(id: phone.id, name: phone.name)) }, "状态：配对中")
    t.check(phone.confirmPairing(s), "收到 Mac 的 paired")
    t.check(h.waitConnected(phone, from: start), "状态：已连接")
    t.check(h.trust.isTrusted(id: phone.id, publicKey: phone.publicKey), "Mac 保存了手机 id 与公钥")

    s.sendObject(FakePhone.bg(mmol: 6.6, ts: sampleTs, trend: "SINGLE_UP"))
    s.sendObject(FakePhone.history(ref: sampleTs, points: [[240, 66], [237, 65], [3, 62], [0, 61]]))
    let reading = h.waitReading(from: start)
    t.equal(reading?.glucose, 6.6, "收到 bg：6.6")
    t.equal(reading?.trendValue, .singleUp, "bg 趋势 SINGLE_UP")
    t.equal(reading?.measuredAt, Date(timeIntervalSince1970: sampleTs / 1000), "bg 测量时间 = ts")
    let history = h.events.wait(from: start) { $0.history != nil }?.history
    t.equal(history?.samples.count, 4, "收到 history：4 个点")
    t.equal(history?.samples.first?.mmol, 6.6, "history 最早点 6.6")
    t.check(history?.targetRange?.high == 7.8, "history 目标范围 3.9–7.8")
    s.send(ControlMessage.ping)
    t.equal(s.receive()?.type, "pong", "手机 ping → Mac pong（Mac→手机方向加密正确）")
    t.check(h.disconnect(s), "手机断开后 Mac 回到等待状态")
}

private func trustedReconnectSkipsPairing(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("第二次连接（已互相信任）→ 不配对")
    let start = h.mark
    let promptsBefore = h.prompts.count
    guard let s = phone.handshake(port: h.port) else { return t.check(false, "握手") }
    t.check(s.phoneKnown, "手机已信任 Mac")
    t.equal(s.exchangeTrust(), true, "Mac 已信任手机 → trust.known=true")
    t.check(h.waitConnected(phone, from: start), "直接进入数据阶段")
    s.sendObject(FakePhone.bg(mmol: 7.2, ts: sampleTs + 60_000))
    t.equal(h.waitReading(from: start)?.glucose, 7.2, "收到 bg：7.2")
    s.sendObject(["type": "sync"])
    s.sendObject(["type": "something-new", "x": 1])
    s.send(ControlMessage.ping)
    t.equal(s.receive()?.type, "pong", "未知 type 被忽略，连接正常")
    t.equal(h.prompts.count, promptsBefore, "没有弹出配对确认")
    t.check(h.disconnect(s), "断开")
}

private func unpairRequiresPairingAgain(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("取消配对 → 再次连接需要重新配对")
    let start = h.mark
    guard let connected = phone.connect(port: h.port), h.waitConnected(phone, from: start) else {
        return t.check(false, "已信任的手机连上")
    }
    h.link.unpair(phoneId: phone.id)
    t.check(connected.connection.waitClosed(), "取消配对立即断开该手机")
    t.check(h.waitUntil { !h.trust.isTrusted(id: phone.id, publicKey: phone.publicKey) }, "信任已删除")
    _ = h.waitState(from: start) { $0 == .listening }

    let again = h.mark
    let promptsBefore = h.prompts.count
    guard let s = phone.handshake(port: h.port) else { return t.check(false, "握手") }
    t.check(s.phoneKnown, "手机仍然信任 Mac")
    t.equal(s.exchangeTrust(), false, "Mac 不再信任 → known=false")
    t.equal(h.prompts.item(at: promptsBefore)?.sas, s.sas, "再次弹出配对确认，配对码一致")
    t.check(phone.confirmPairing(s), "重新配对完成")
    t.check(h.waitConnected(phone, from: again), "重新进入数据阶段")
    s.sendObject(FakePhone.bg(mmol: 5.4, ts: sampleTs + 120_000))
    t.equal(h.waitReading(from: again)?.glucose, 5.4, "数据正常")
    t.check(h.disconnect(s), "断开")
}

private func timeoutsDropConnection(_ t: TestRun, _ h: LinkHarness, _ phone: FakePhone) {
    t.section("ping 与超时（缩短的计时）")
    let idle = TestConnection(port: h.port)
    t.check(idle.waitClosed(timeout: 3), "握手超时 → 断开")

    let start = h.mark
    guard let s = phone.connect(port: h.port), h.waitConnected(phone, from: start) else {
        return t.check(false, "连上")
    }
    t.equal(s.receive(timeout: 2)?.type, "ping", "空闲时 Mac 主动 ping")
    s.send(ControlMessage.pong)
    let silentSince = Date()
    t.check(s.connection.waitClosed(timeout: 4), "手机静默超过 silenceTimeout → 断开")
    t.check(Date().timeIntervalSince(silentSince) >= 0.8, "不早于超时时间断开")
}
