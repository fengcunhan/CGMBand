import Foundation

extension PhoneLinkEvent {
    var state: PhoneLinkState? {
        if case let .state(s) = self { return s }
        return nil
    }

    var reading: PhoneReading? {
        if case let .reading(r) = self { return r }
        return nil
    }

    var history: PhoneHistory? {
        if case let .history(h) = self { return h }
        return nil
    }

    var listenerPort: UInt16? {
        if case let .listenerReady(port) = self { return port }
        return nil
    }
}

/// 在回环地址上启动 PhoneLink（不发布 Bonjour），记录所有事件；配对确认自动应答。
final class LinkHarness {
    let link: PhoneLink
    let trust: TrustStore
    let events = Inbox<PhoneLinkEvent>()
    let prompts = Inbox<(name: String, sas: String)>()
    let acceptPairing = Locked(true)
    private(set) var port: UInt16 = 0

    init(identity: DesktopIdentity, trust: TrustStore, timing: PhoneLinkTiming, port: UInt16 = 0) {
        var options = PhoneLink.Options()
        options.port = port
        options.advertise = false
        options.loopbackOnly = true
        options.serviceName = "测试 Mac"
        options.timing = timing
        self.trust = trust
        link = PhoneLink(identity: identity, trust: trust, options: options,
                         eventQueue: DispatchQueue(label: "test.phonelink.events"))
        link.onEvent = { [events] in events.put($0) }
        link.confirmPairing = { [prompts, acceptPairing] name, sas, completion in
            prompts.put((name, sas))
            completion(acceptPairing.get())
        }
    }

    func start() -> Bool {
        link.start()
        port = events.wait(timeout: 5) { $0.listenerPort != nil }?.listenerPort ?? 0
        return port != 0
    }

    func stop() {
        let mark = events.count
        link.stop()
        _ = waitState(from: mark) { $0 == .stopped }
    }

    var mark: Int { events.count }

    func waitState(from start: Int, timeout: TimeInterval = 3, _ match: (PhoneLinkState) -> Bool) -> Bool {
        events.wait(from: start, timeout: timeout) { $0.state.map(match) ?? false } != nil
    }

    func waitConnected(_ phone: FakePhone, from start: Int) -> Bool {
        waitState(from: start) { $0 == .connected(PhoneInfo(id: phone.id, name: phone.name)) }
    }

    /// 关闭手机连接并等待 Mac 回到「等待手机连接」。
    func disconnect(_ session: FakeSession) -> Bool {
        let start = mark
        session.connection.close()
        return waitState(from: start) { $0 == .listening }
    }

    func waitReading(from start: Int, timeout: TimeInterval = 3) -> PhoneReading? {
        events.wait(from: start, timeout: timeout) { $0.reading != nil }?.reading
    }

    func readingCount(from start: Int) -> Int {
        events.snapshot().dropFirst(start).filter { $0.reading != nil }.count
    }

    func waitUntil(timeout: TimeInterval = 2, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.02)
        }
        return condition()
    }
}
