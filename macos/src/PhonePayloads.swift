import Foundation

/// 手机（iCan）给出的趋势枚举，与手环协议一致。
enum GlucoseTrend: String, CaseIterable {
    case doubleUp = "DOUBLE_UP"
    case singleUp = "SINGLE_UP"
    case fortyFiveUp = "FORTY_FIVE_UP"
    case flat = "FLAT"
    case fortyFiveDown = "FORTY_FIVE_DOWN"
    case singleDown = "SINGLE_DOWN"
    case doubleDown = "DOUBLE_DOWN"
    case none = "NONE"

    var arrow: String {
        switch self {
        case .doubleUp: return "⇈"
        case .singleUp: return "↑"
        case .fortyFiveUp: return "↗"
        case .flat: return "→"
        case .fortyFiveDown: return "↘"
        case .singleDown: return "↓"
        case .doubleDown: return "⇊"
        case .none: return ""
        }
    }
}

/// {"type":"bg","value":"6.6","unit":"mmol/L","mmol":6.6,"time":"16:33","ts":…,"trend":"FLAT","delta":0.1,"connected":true}
struct PhoneReading: Decodable, Equatable {
    let value: String?
    let unit: String?
    let mmol: Double?
    let time: String?
    let ts: Double?
    let trend: String?
    let delta: Double?
    let connected: Bool?

    static let plausibleRange = 0.5...40.0

    /// 有效的 mmol/L 数值；LO / HI 等非数字读数为 nil。
    var glucose: Double? {
        guard let m = mmol, m.isFinite, PhoneReading.plausibleRange.contains(m) else { return nil }
        return m
    }

    /// 测量时间；`ts` 为 null 或不合理时为 nil（时间未知）。
    var measuredAt: Date? {
        guard let ms = ts, ms.isFinite, ms > 0 else { return nil }
        return Date(timeIntervalSince1970: ms / 1000)
    }

    var trendValue: GlucoseTrend { trend.flatMap(GlucoseTrend.init(rawValue:)) ?? .none }

    var sensorConnected: Bool { connected ?? true }

    /// iCan 显示的原文（如 LO / HI），限制长度用于显示。
    var displayText: String? {
        guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return nil }
        return String(v.prefix(8))
    }
}

/// 一个带测量时间的读数：历史、曲线和统计的基本单位。
struct GlucoseSample: Equatable {
    let time: Date
    let mmol: Double
}

/// {"type":"history","ref":…,"range":[3.9,7.8],"points":[[240,66],…,[0,61]]}
struct PhoneHistory: Decodable, Equatable {
    let ref: Double?
    let range: [Double]?
    let points: [[Double]]?

    static let maxPoints = 2000

    /// 时间 = ref − 分钟数，数值 = mmol×10 / 10；按时间从早到晚。
    var samples: [GlucoseSample] {
        guard let ref, ref.isFinite, ref > 0 else { return [] }
        let refDate = Date(timeIntervalSince1970: ref / 1000)
        let parsed = (points ?? []).prefix(PhoneHistory.maxPoints).compactMap { p -> GlucoseSample? in
            guard p.count >= 2, p[0].isFinite, p[0] >= 0, p[1].isFinite else { return nil }
            let mmol = p[1] / 10
            guard PhoneReading.plausibleRange.contains(mmol) else { return nil }
            return GlucoseSample(time: refDate.addingTimeInterval(-p[0] * 60), mmol: mmol)
        }
        return parsed.sorted { $0.time < $1.time }
    }

    var targetRange: (low: Double, high: Double)? {
        guard let r = range, r.count == 2, r[0].isFinite, r[1].isFinite,
              r[0] > 0, r[0] < r[1], r[1] < 40 else { return nil }
        return (r[0], r[1])
    }
}
