import AppKit

/// 配对确认弹窗。非模态显示（不阻塞主线程，网络事件和界面照常更新），
/// 手机断开或被新连接取代时可按配对码关闭对应弹窗。
@MainActor
final class PairingPrompter: NSObject {
    private var alert: NSAlert?
    private var sas: String?
    private var completion: ((Bool) -> Void)?

    func present(phoneName: String, sas: String, completion: @escaping (Bool) -> Void) {
        resolve(false)   // 同一时间只保留一个请求，旧的视为拒绝

        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = "手机「\(phoneName)」请求配对"
        alert.informativeText = "配对码 \(sas)\n请确认与手机上显示的一致"
        let pair = alert.addButton(withTitle: "配对")
        pair.target = self
        pair.action = #selector(pairTapped)
        let reject = alert.addButton(withTitle: "拒绝")
        reject.target = self
        reject.action = #selector(rejectTapped)
        reject.keyEquivalent = "\u{1b}"
        alert.layout()

        self.alert = alert
        self.sas = sas
        self.completion = completion

        let window = alert.window
        window.level = .floating
        window.collectionBehavior.insert(.moveToActiveSpace)
        window.center()
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    /// 连接已断开：关闭对应的弹窗，不再回调。
    func cancel(sas: String) {
        guard sas == self.sas else { return }
        dismiss()
    }

    @objc private func pairTapped() { resolve(true) }
    @objc private func rejectTapped() { resolve(false) }

    private func resolve(_ accepted: Bool) {
        let callback = completion
        dismiss()
        callback?(accepted)
    }

    private func dismiss() {
        alert?.window.orderOut(nil)
        alert = nil
        sas = nil
        completion = nil
    }
}
