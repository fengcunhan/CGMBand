import SwiftUI

@main
struct CGMMonitorApp: App {
    // 主窗口、设置窗口与菜单栏由 AppDelegate 管理（NSStatusItem 才能给文字上色；窗口可从菜单栏随时打开）
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings { EmptyView() }
            .commands {
                CommandGroup(replacing: .appSettings) {
                    Button("设置…") { appDelegate.showSettings() }
                        .keyboardShortcut(",", modifiers: .command)
                }
            }
    }
}
