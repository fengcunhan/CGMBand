import Foundation

/// 把 PhoneLink（网络队列）接到 CGMModel（主线程）：身份密钥、信任列表、配对弹窗、事件转发。
@MainActor
final class PhoneBridge {
    private weak var model: CGMModel?
    private let trust = TrustStore()
    private let prompter = PairingPrompter()
    private var link: PhoneLink?

    init(model: CGMModel) {
        self.model = model
    }

    var trustedPhones: [TrustedPhone] { trust.all() }

    func start() {
        if link == nil { link = makeLink() }
        link?.start()
    }

    func stop() {
        link?.stop()
    }

    func unpair(id: String) {
        let name = trust.phone(id: id)?.name ?? "手机"
        trust.remove(id: id)
        link?.unpair(phoneId: id)   // 正连着的话断开
        model?.trustedPhones = trust.all()
        model?.addLog("已取消与「\(name)」的配对")
    }

    private func makeLink() -> PhoneLink? {
        let identity: DesktopIdentity
        do {
            identity = try DesktopIdentity.loadOrCreate()
        } catch {
            model?.phoneState = .failed("无法读取本机身份密钥")
            model?.addLog("手机推送不可用：\(error)")
            return nil
        }
        let link = PhoneLink(identity: identity, trust: trust, eventQueue: .main)
        link.onEvent = { [weak self] event in
            MainActor.assumeIsolated { self?.handle(event) }
        }
        link.confirmPairing = { [weak self] phoneName, sas, completion in
            MainActor.assumeIsolated {
                guard let self else { return completion(false) }
                self.prompter.present(phoneName: phoneName, sas: sas, completion: completion)
            }
        }
        model?.addLog("本机设备 ID \(identity.deviceId)")
        return link
    }

    private func handle(_ event: PhoneLinkEvent) {
        guard let model else { return }
        switch event {
        case let .state(state):
            model.phoneState = state
            model.trustedPhones = trust.all()   // 配对完成 / 撤销后刷新列表
            if case let .failed(message) = state { model.addLog(message) }
        case let .listenerReady(port): model.addLog("手机推送：已在端口 \(port) 监听")
        case let .serviceRegistered(name): model.addLog("已发布 Bonjour 服务「\(name)」")
        case let .reading(reading): model.applyPhoneReading(reading)
        case let .history(history): model.applyPhoneHistory(history)
        case let .pairingCancelled(sas): prompter.cancel(sas: sas)
        case let .log(message): model.addLog("手机：\(message)")
        }
    }
}
