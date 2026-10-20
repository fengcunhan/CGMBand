import AppKit
import SwiftUI

/// 应用生命周期：主窗口、设置窗口（AppKit 托管，便于从齿轮按钮、⌘, 和菜单栏打开）+ 菜单栏状态项。
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let model: CGMModel
    private var statusBar: StatusBarController?
    private var mainWindow: NSWindow?
    private var settingsWindow: NSWindow?

    override init() {
        model = CGMModel()
        super.init()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusBar = StatusBarController(model: model,
                                        showMainWindow: { [weak self] in self?.showMainWindow() },
                                        showSettings: { [weak self] in self?.showSettings() })
        model.start()
        showMainWindow()
    }

    func applicationWillTerminate(_ notification: Notification) {
        model.shutdown()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { showMainWindow() }
        return true
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false   // 关掉窗口后菜单栏继续显示血糖
    }

    func showMainWindow() {
        let window = mainWindow ?? makeMainWindow()
        mainWindow = window
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    func showSettings() {
        let window = settingsWindow ?? makeSettingsWindow()
        settingsWindow = window
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    /// 窗口大小跟随内容（约 480×700），不留空白。
    private func makeMainWindow() -> NSWindow {
        let root = DashboardView(model: model, openSettings: { [weak self] in self?.showSettings() })
        let window = makeWindow(NSHostingController(rootView: root), title: "血糖监测")
        window.setFrameAutosaveName("CGMDashboardWindow")
        return window
    }

    private func makeSettingsWindow() -> NSWindow {
        makeWindow(NSHostingController(rootView: SettingsView(model: model)), title: "设置")
    }

    private func makeWindow<Content: View>(_ host: NSHostingController<Content>, title: String) -> NSWindow {
        host.sizingOptions = [.preferredContentSize]
        let window = NSWindow(contentViewController: host)
        window.title = title
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.isReleasedWhenClosed = false
        window.setContentSize(host.view.fittingSize)
        window.center()
        return window
    }
}
