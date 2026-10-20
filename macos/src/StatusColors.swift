import AppKit

/// 菜单栏文字颜色：浅色菜单栏用加深的版本保证可读，深色菜单栏用系统色。
enum StatusColors {
    static func color(for tone: GlucoseTone) -> NSColor {
        switch tone {
        case .inRange: return green
        case .high: return orange
        case .low: return red
        case .stale: return stale
        case .none: return .labelColor
        }
    }

    private static let green = dynamic(light: NSColor(srgbRed: 0.14, green: 0.54, blue: 0.24, alpha: 1), dark: .systemGreen)
    private static let orange = dynamic(light: NSColor(srgbRed: 0.79, green: 0.33, blue: 0.0, alpha: 1), dark: .systemOrange)
    private static let red = dynamic(light: NSColor(srgbRed: 0.84, green: 0.0, blue: 0.08, alpha: 1),
                                     dark: NSColor(srgbRed: 1.0, green: 0.42, blue: 0.40, alpha: 1))
    private static let stale = dynamic(light: NSColor(white: 0, alpha: 0.5), dark: NSColor(white: 1, alpha: 0.7))

    static func isDark(_ appearance: NSAppearance) -> Bool {
        appearance.bestMatch(from: [.aqua, .darkAqua, .vibrantLight, .vibrantDark]).map {
            $0 == .darkAqua || $0 == .vibrantDark
        } ?? false
    }

    private static func dynamic(light: NSColor, dark: NSColor) -> NSColor {
        NSColor(name: nil) { isDark($0) ? dark : light }
    }
}

/// 把彩色标题画成非模板图片。macOS 26 的菜单栏会给文字加毛玻璃混合，导致 attributedTitle 的颜色发白；
/// 图片则保持原色。颜色按菜单栏按钮的外观（深/浅，随壁纸变化）解析成固定值后再绘制。
enum StatusTitleImage {
    static func make(_ title: String, tone: GlucoseTone, appearance: NSAppearance) -> NSImage {
        var resolved = NSColor.labelColor
        appearance.performAsCurrentDrawingAppearance {
            resolved = NSColor(cgColor: StatusColors.color(for: tone).cgColor) ?? .labelColor
        }
        let font = NSFont.monospacedDigitSystemFont(ofSize: NSFont.menuBarFont(ofSize: 0).pointSize, weight: .semibold)
        let text = NSAttributedString(string: title, attributes: [.foregroundColor: resolved, .font: font])
        let size = text.size()
        let image = NSImage(size: NSSize(width: ceil(size.width), height: ceil(size.height)), flipped: false) { _ in
            text.draw(at: .zero)
            return true
        }
        image.isTemplate = false
        image.accessibilityDescription = title
        return image
    }
}
