import Foundation

enum GlucoseTone: Equatable {
    case none      // 没有数据或无法判断
    case stale     // 过期：灰色
    case low
    case inRange
    case high
}

/// 菜单栏需要的全部输入，与数据来源无关。
struct GlucoseSnapshot {
    var mmol: Double?
    var rawText: String?          // 非数字读数（LO / HI）
    var trend: GlucoseTrend
    var measuredAt: Date?
    var lowLimit: Double
    var highLimit: Double
    var linkProblem: String?      // 例如「手机未连接」；正常时为 nil
    var sensorConnected: Bool
}

/// 菜单栏标题与菜单首行的纯文本表示（不依赖 AppKit，便于测试）。
struct GlucoseDisplay: Equatable {
    static let staleAfter: TimeInterval = 10 * 60

    let title: String          // 例如 "6.3 →"
    let tone: GlucoseTone
    let header: String         // 例如 "6.3 mmol/L → · 3分钟前"
    let staleReason: String?

    init(_ snapshot: GlucoseSnapshot, now: Date = Date()) {
        guard let value = GlucoseDisplay.valueText(snapshot) else {
            title = "--"
            tone = .none
            header = "暂无血糖数据"
            staleReason = nil
            return
        }
        let arrow = snapshot.trend.arrow
        let unit = snapshot.mmol == nil ? "" : " mmol/L"
        title = arrow.isEmpty ? value : "\(value) \(arrow)"
        let reading = arrow.isEmpty ? "\(value)\(unit)" : "\(value)\(unit) \(arrow)"
        let reason = GlucoseDisplay.staleReason(snapshot, now: now)
        staleReason = reason
        tone = reason == nil ? GlucoseDisplay.level(snapshot) : .stale
        let detail = reason ?? snapshot.measuredAt.map { GlucoseDisplay.age(of: $0, now: now) } ?? ""
        header = "\(reading) · \(detail)"
    }

    static func format(_ mmol: Double) -> String { String(format: "%.1f", mmol) }

    /// 「刚刚」(< 1 分钟)「3分钟前」(< 1 小时)「2小时前」(< 1 天)「3天前」，与手机端一致。
    static func age(of date: Date, now: Date = Date()) -> String {
        let seconds = Int(now.timeIntervalSince(date))
        if seconds < 60 { return "刚刚" }
        if seconds < 3600 { return "\(seconds / 60)分钟前" }
        if seconds < 86400 { return "\(seconds / 3600)小时前" }
        return "\(seconds / 86400)天前"
    }

    /// 主窗口左上角：「3分钟前 · 10-20 14:42」；ts 为 null 时「时间未知」；还没有读数时「暂无数据」。
    static func readingTimeLabel(measuredAt: Date?, hasReading: Bool, now: Date = Date()) -> String {
        guard hasReading else { return "暂无数据" }
        guard let measuredAt else { return "时间未知" }
        return "\(age(of: measuredAt, now: now)) · \(dayTime.string(from: measuredAt))"
    }

    static func clock(_ date: Date) -> String { hourMinute.string(from: date) }

    private static let dayTime = formatter("MM-dd HH:mm")
    private static let hourMinute = formatter("HH:mm")

    private static func formatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = format
        return f
    }

    private static func valueText(_ s: GlucoseSnapshot) -> String? {
        if let mmol = s.mmol { return format(mmol) }
        return s.rawText
    }

    private static func staleReason(_ s: GlucoseSnapshot, now: Date) -> String? {
        if let problem = s.linkProblem { return problem }
        if !s.sensorConnected { return "传感器未连接" }
        guard let measured = s.measuredAt else { return "测量时间未知" }
        if now.timeIntervalSince(measured) > staleAfter { return "\(age(of: measured, now: now))，已过期" }
        return nil
    }

    private static func level(_ s: GlucoseSnapshot) -> GlucoseTone {
        guard let mmol = s.mmol else {
            let text = (s.rawText ?? "").uppercased()
            if text.hasPrefix("LO") { return .low }
            if text.hasPrefix("HI") { return .high }
            return .none
        }
        if mmol < s.lowLimit { return .low }
        if mmol > s.highLimit { return .high }
        return .inRange
    }
}
