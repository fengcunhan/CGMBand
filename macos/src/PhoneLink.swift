import Foundation
import Network

enum PhoneLinkState: Equatable {
    case stopped
    case listening
    case pairing(PhoneInfo)
    case connected(PhoneInfo)
    case failed(String)

    var connectedPhone: PhoneInfo? {
        if case let .connected(info) = self { return info }
        return nil
    }
}

enum PhoneLinkEvent {
    case listenerReady(port: UInt16)
    case serviceRegistered(name: String)
    case state(PhoneLinkState)
    case reading(PhoneReading)
    case history(PhoneHistory)
    /// 某个仍在显示的配对弹窗（按配对码区分）已失效，应关闭。
    case pairingCancelled(sas: String)
    case log(String)
}

/// Mac 端接收手机推送的服务：NWListener + Bonjour `_cgmband._tcp`，同一时间只保留一个手机连接：
/// 新连接发来有效 hello 后取代当前连接（只连不发 hello 的探测连接不会挤掉正在推送的手机）。
/// 内部状态只在私有串行队列上访问；事件和配对确认在 `eventQueue` 上回调。
/// `onEvent` / `confirmPairing` 须在 `start()` 之前设置。
final class PhoneLink {
    typealias PairingConfirm = (_ phoneName: String, _ sas: String, _ completion: @escaping (Bool) -> Void) -> Void

    struct Options {
        var port: UInt16 = DesktopProtocol.defaultPort  // 被占用时退回系统分配；0 = 一律系统分配
        var advertise = true              // 发布 Bonjour 服务
        var loopbackOnly = false          // 测试用：只接受本机回环连接
        var serviceName: String? = nil    // nil = Mac 的显示名
        var timing = PhoneLinkTiming()
        var restartDelay: TimeInterval = 5
        var preferredPortAttempts = 3            // 首选端口被占用时先重试几次（例如旧进程还在退出）
        var preferredPortRetryDelay: TimeInterval = 0.5
    }

    var onEvent: ((PhoneLinkEvent) -> Void)?
    var confirmPairing: PairingConfirm?

    let identity: DesktopIdentity
    let trust: TrustStore
    private let options: Options
    private let queue = DispatchQueue(label: "local.cgm.monitor.phonelink")
    private let eventQueue: DispatchQueue
    private var listener: NWListener?
    private var session: PhoneSession?          // 当前手机（已发送有效 hello）
    private var pending: [PhoneSession] = []    // 刚连上、还没发 hello 的连接
    private let maxPending = 4
    private var running = false
    private var usePreferredPort = true         // 首选端口重试仍失败后，本轮改用系统分配
    private var preferredPortFailures = 0
    private var listenerOnPreferredPort = false
    private var retiring: NWListener?           // 正在取消的旧监听：等它释放端口后才绑定新的
    private var macName = "Mac"

    init(identity: DesktopIdentity, trust: TrustStore, options: Options = Options(), eventQueue: DispatchQueue = .main) {
        self.identity = identity
        self.trust = trust
        self.options = options
        self.eventQueue = eventQueue
    }

    func start() {
        queue.async { [self] in
            guard !running else { return }
            running = true
            usePreferredPort = true
            preferredPortFailures = 0
            macName = options.serviceName ?? Host.current().localizedName ?? "Mac"
            bindWhenReady()
        }
    }

    func stop() {
        queue.async { [self] in
            running = false
            retire(listener)
            listener = nil
            let all = pending + [session].compactMap { $0 }
            all.forEach { $0.close("已停止接收手机推送") }
            pending = []
            session = nil
            emit(.state(.stopped))
        }
    }

    /// 取消配对：删除信任；如果这台手机正连着，立即断开。
    func unpair(phoneId: String) {
        queue.async { [self] in
            trust.remove(id: phoneId)
            if session?.phone?.id == phoneId { session?.close("已取消配对") }
        }
    }

    // MARK: - 监听

    private func makeListener() {
        // 不限定地址族（IPv4/IPv6 双栈）：Android NSD 常常只解析到 Mac 的 IPv6 链路本地地址（fe80::…）。
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true   // 快速重启时可重新绑定（不会让两个实例共用端口）
        if options.loopbackOnly {
            params.requiredInterfaceType = .loopback
        } else {
            params.acceptLocalOnly = true       // 只接受本地网络（含链路本地）的连接
        }
        let preferred = usePreferredPort && options.port != 0
        listenerOnPreferredPort = preferred
        let port = preferred ? (NWEndpoint.Port(rawValue: options.port) ?? .any) : .any
        let listener: NWListener
        do {
            listener = try NWListener(using: params, on: port)
        } catch {
            return listenerFailed("\(error)")
        }
        if options.advertise {
            let txt = NWTXTRecord(["id": identity.deviceId, "proto": String(DesktopProtocol.version)])
            listener.service = NWListener.Service(name: macName, type: DesktopProtocol.serviceType,
                                                  domain: nil, txtRecord: txt)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener else { return }
            self.listenerStateChanged(listener, state)
        }
        listener.serviceRegistrationUpdateHandler = { [weak self] change in
            if case let .add(endpoint) = change, case let .service(name, _, _, _) = endpoint {
                self?.emit(.serviceRegistered(name: name))
            }
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        self.listener = listener
        listener.start(queue: queue)
    }

    private func listenerStateChanged(_ listener: NWListener, _ state: NWListener.State) {
        guard listener === self.listener else { return }
        switch state {
        case .ready:
            preferredPortFailures = 0
            emit(.listenerReady(port: listener.port?.rawValue ?? 0))
            emit(.state(currentState()))
        case let .waiting(error):
            emit(.log("等待网络：\(error)"))
        case let .failed(error):
            self.listener = nil
            retire(listener)
            listenerFailed("\(error)")
        default:
            break
        }
    }

    /// 只有在没有正在运行、也没有正在取消的监听时才绑定新的，避免新旧监听争抢同一端口。
    private func bindWhenReady() {
        guard running, listener == nil, retiring == nil else { return }
        makeListener()
    }

    /// 取消旧监听，等它报告 .cancelled（端口已释放）后再允许绑定；2 秒兜底。
    private func retire(_ old: NWListener?) {
        guard let old else { return }
        retiring = old
        old.stateUpdateHandler = { [weak self, weak old] state in
            guard case .cancelled = state, let self, let old else { return }
            self.retired(old)
        }
        old.cancel()
        queue.asyncAfter(deadline: .now() + 2) { [weak self, weak old] in
            guard let self, let old else { return }
            self.retired(old)
        }
    }

    private func retired(_ old: NWListener) {
        guard retiring === old else { return }
        retiring = nil
        bindWhenReady()
    }

    private func listenerFailed(_ reason: String) {
        guard running else { return }
        if listenerOnPreferredPort {
            preferredPortFailures += 1
            if preferredPortFailures < options.preferredPortAttempts {
                emit(.log("端口 \(options.port) 暂不可用（\(reason)），稍后重试"))
                queue.asyncAfter(deadline: .now() + options.preferredPortRetryDelay) { [weak self] in
                    self?.bindWhenReady()
                }
                return
            }
            emit(.log("端口 \(options.port) 不可用（\(reason)），改用系统分配的端口"))
            usePreferredPort = false
            return bindWhenReady()
        }
        emit(.state(.failed("本地网络服务出错：\(reason)")))
        usePreferredPort = true
        preferredPortFailures = 0
        queue.asyncAfter(deadline: .now() + options.restartDelay) { [weak self] in self?.bindWhenReady() }
    }

    private func currentState() -> PhoneLinkState {
        guard running else { return .stopped }
        guard let session, let phone = session.phone else { return .listening }
        switch session.phase {
        case .data: return .connected(phone)
        case .pairing: return .pairing(phone)
        default: return .listening
        }
    }

    // MARK: - 连接

    private func accept(_ connection: NWConnection) {
        guard running else { return connection.cancel() }
        if pending.count >= maxPending { pending.first?.close("待握手的连接过多") }
        let channel = LineChannel(connection: connection, queue: queue)
        emit(.log("收到连接：\(channel.peerDescription)"))
        let env = SessionEnvironment(identity: identity, macName: macName, trust: trust,
                                     timing: options.timing, queue: queue)
        let newSession = PhoneSession(channel: channel, env: env)
        newSession.onEvent = { [weak self, weak newSession] event in
            guard let self, let newSession else { return }
            self.handle(event, from: newSession)
        }
        pending.append(newSession)
        newSession.start()
    }

    private func promote(_ source: PhoneSession) {
        guard let index = pending.firstIndex(where: { $0 === source }) else { return }
        pending.remove(at: index)
        if let old = session { old.close("被新的连接替换") }
        session = source
    }

    private func handle(_ event: SessionEvent, from source: PhoneSession) {
        if case let .closed(reason, cancelledSAS) = event {
            return sessionClosed(source, reason: reason, cancelledSAS: cancelledSAS)
        }
        if case .identified = event { promote(source) }
        guard source === session else { return }
        switch event {
        case let .pairingNeeded(phone, sas):
            emit(.state(.pairing(phone)))
            requestConfirmation(phone: phone, sas: sas, session: source)
        case let .connected(phone):
            emit(.log("「\(phone.name)」已连接"))
            emit(.state(.connected(phone)))
        case let .reading(reading): emit(.reading(reading))
        case let .history(history): emit(.history(history))
        case let .log(message): emit(.log(message))
        case .identified, .closed: break
        }
    }

    private func sessionClosed(_ source: PhoneSession, reason: String, cancelledSAS: String?) {
        pending.removeAll { $0 === source }
        if let cancelledSAS { emit(.pairingCancelled(sas: cancelledSAS)) }
        let who = source.phone.map { "「\($0.name)」" } ?? "连接"
        emit(.log("\(who)断开：\(reason)"))
        guard source === session else { return }
        session = nil
        emit(.state(running ? .listening : .stopped))
    }

    private func requestConfirmation(phone: PhoneInfo, sas: String, session target: PhoneSession) {
        guard let confirm = confirmPairing else { return target.resolvePairing(false) }
        eventQueue.async { [weak self, weak target] in
            confirm(phone.name, sas) { accepted in
                self?.queue.async { target?.resolvePairing(accepted) }
            }
        }
    }

    private func emit(_ event: PhoneLinkEvent) {
        guard let handler = onEvent else { return }
        eventQueue.async { handler(event) }
    }
}
