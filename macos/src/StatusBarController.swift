import AppKit
import Combine

/// 菜单栏：彩色血糖值 + 菜单（状态、显示主窗口、设置…、已配对的手机、退出）。
@MainActor
final class StatusBarController: NSObject, NSMenuDelegate {
    private let model: CGMModel
    private let showMainWindow: () -> Void
    private let showSettings: () -> Void
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private var timer: Timer?
    private var modelChanges: AnyCancellable?
    private var appearanceObservation: NSKeyValueObservation?
    private var renderedKey: String?   // 标题/颜色/外观都没变就不重设图片（设图片本身会触发外观 KVO）

    init(model: CGMModel, showMainWindow: @escaping () -> Void, showSettings: @escaping () -> Void) {
        self.model = model
        self.showMainWindow = showMainWindow
        self.showSettings = showSettings
        super.init()
        menu.delegate = self
        menu.autoenablesItems = false
        item.menu = menu
        refresh()
        modelChanges = model.objectWillChange
            .debounce(for: .milliseconds(150), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.refresh() } }
        // 「几分钟前」与过期判断依赖当前时间，至少每 30 秒刷新一次
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
        // 状态项挂到菜单栏窗口后外观才确定；深浅也会随壁纸变化，颜色要跟着重新解析
        appearanceObservation = item.button?.observe(\.effectiveAppearance) { [weak self] _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.refresh() } }
        }
    }

    func refresh() {
        guard let button = item.button else { return }
        let display = model.display
        button.toolTip = "\(display.header)\n\(model.connectionText)"
        button.setAccessibilityLabel(display.header)
        let appearance = button.effectiveAppearance
        let key = "\(display.title)|\(display.tone)|\(appearance.name.rawValue)"
        guard key != renderedKey else { return }
        renderedKey = key
        button.title = ""
        button.image = StatusTitleImage.make(display.title, tone: display.tone, appearance: appearance)
    }

    // MARK: - 菜单

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(infoItem(model.display.header))
        menu.addItem(infoItem(model.connectionText))
        menu.addItem(.separator())
        menu.addItem(actionItem("显示主窗口", #selector(showWindowTapped)))
        menu.addItem(actionItem("设置…", #selector(settingsTapped), key: ","))
        menu.addItem(phonesMenuItem())
        menu.addItem(.separator())
        menu.addItem(actionItem("退出", #selector(quitTapped), key: "q"))
    }

    private func infoItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func actionItem(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        return item
    }

    private func phonesMenuItem() -> NSMenuItem {
        let submenu = NSMenu()
        submenu.autoenablesItems = false
        let phones = model.trustedPhones
        if phones.isEmpty { submenu.addItem(infoItem("暂无已配对的手机")) }
        let connectedId = model.phoneState.connectedPhone?.id
        for phone in phones {
            let unpair = actionItem("取消配对", #selector(unpairTapped(_:)))
            unpair.representedObject = phone.id
            let phoneMenu = NSMenu()
            phoneMenu.addItem(unpair)
            let title = phone.id == connectedId ? "\(phone.name)（已连接）" : phone.name
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.submenu = phoneMenu
            submenu.addItem(item)
        }
        let parent = NSMenuItem(title: "已配对的手机", action: nil, keyEquivalent: "")
        parent.submenu = submenu
        return parent
    }

    @objc private func showWindowTapped() { showMainWindow() }

    @objc private func settingsTapped() { showSettings() }

    @objc private func unpairTapped(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        model.unpairPhone(id: id)
    }

    @objc private func quitTapped() { NSApp.terminate(nil) }
}
