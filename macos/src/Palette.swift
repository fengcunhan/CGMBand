import AppKit
import SwiftUI

/// 与手机端 res/values(-night)/colors.xml 一致的配色，自动适配浅色 / 深色模式。
enum Palette {
    static let page = dynamic(light: 0xF4F5F7, dark: 0x0F1115)
    static let card = dynamic(light: 0xFFFFFF, dark: 0x1A1D23)
    static let textPrimary = dynamic(light: 0x111827, dark: 0xF3F4F6)
    static let textSecondary = dynamic(light: 0x6B7280, dark: 0x9CA3AF)
    static let chip = dynamic(light: 0xF0F1F3, dark: 0x262A31)
    static let segmentTrack = dynamic(light: 0xF0F1F3, dark: 0x262A31)
    static let segmentSelected = dynamic(light: 0xFFFFFF, dark: 0x3A3F48)
    static let grid = dynamic(light: 0xE5E7EB, dark: 0x2A2E35)
    static let band = dynamic(light: 0xEEF4F4, dark: 0x1C2626)
    static let today = dynamic(light: 0x2B3440, dark: 0xE5E7EB)
    static let yesterday = dynamic(light: 0xD5D8DD, dark: 0x4B5563)

    static let low = fixed(0xE53935)
    static let inRange = fixed(0x22A06B)
    static let high = fixed(0xF7931E)
    static let stale = fixed(0x9CA3AF)

    static func level(_ level: GlucoseLevel) -> Color {
        switch level {
        case .low: return low
        case .inRange: return today
        case .high: return high
        }
    }

    /// 大号数值的颜色：范围内绿、偏高橙、偏低红，过期 / 未知 / 断开灰。
    static func tone(_ tone: GlucoseTone) -> Color {
        switch tone {
        case .inRange: return inRange
        case .high: return high
        case .low: return low
        case .stale, .none: return stale
        }
    }

    private static func fixed(_ hex: UInt32) -> Color { Color(nsColor: nsColor(hex)) }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { StatusColors.isDark($0) ? nsColor(dark) : nsColor(light) })
    }

    private static func nsColor(_ hex: UInt32) -> NSColor {
        NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
