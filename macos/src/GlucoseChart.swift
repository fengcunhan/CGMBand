import Foundation

// 曲线的纯逻辑，规则与手机端一致（android/…/core/ChartData.kt、ChartCursor.kt、TargetRange.kt）。

/// 可见时间段：结束于下一个整点，5 个等距刻度。
struct ChartWindow: Equatable {
    static let tickIntervals = 4

    let start: Date
    let end: Date
    let ticks: [Date]

    static func forHours(_ hours: Int, now: Date, calendar: Calendar = .current) -> ChartWindow {
        let hourStart = calendar.dateInterval(of: .hour, for: now)?.start ?? now
        let end = calendar.date(byAdding: .hour, value: 1, to: hourStart) ?? now
        let start = calendar.date(byAdding: .hour, value: -hours, to: end) ?? now
        let step = end.timeIntervalSince(start) / Double(tickIntervals)
        let ticks = (0...tickIntervals).map { start.addingTimeInterval(step * Double($0)) }
        return ChartWindow(start: start, end: end, ticks: ticks)
    }
}

enum GlucoseLevel: Equatable {
    case low, inRange, high

    static func of(_ mmol: Double, low: Double, high: Double) -> GlucoseLevel {
        if mmol < low { return .low }
        if mmol > high { return .high }
        return .inRange
    }
}

/// 曲线的一段同色折线（今日曲线在上下限处换色）。
struct ColoredRun: Equatable {
    let level: GlucoseLevel
    let points: [GlucoseSample]
}

/// 一个窗口要画的数据：今日曲线，以及平移到今日时间轴上的昨日曲线。
struct ChartData: Equatable {
    static let day: TimeInterval = 24 * 3600
    static let maxGap: TimeInterval = 15 * 60
    static let minYMax = 12.0
    static let yStep = 3.0

    let window: ChartWindow
    let today: [GlucoseSample]
    let yesterday: [GlucoseSample]
    let yMax: Double

    static func build(_ points: [GlucoseSample], window: ChartWindow) -> ChartData {
        let today = points.filter { $0.time >= window.start && $0.time <= window.end }
            .sorted { $0.time < $1.time }
        let yStart = window.start.addingTimeInterval(-day)
        let yEnd = window.end.addingTimeInterval(-day)
        let yesterday = points.filter { $0.time >= yStart && $0.time <= yEnd }
            .map { GlucoseSample(time: $0.time.addingTimeInterval(day), mmol: $0.mmol) }
            .sorted { $0.time < $1.time }
        let highest = (today + yesterday).map(\.mmol).max() ?? 0
        return ChartData(window: window, today: today, yesterday: yesterday,
                         yMax: max(minYMax, (highest / yStep).rounded(.up) * yStep))
    }

    /// 在缺数据（间隔 > 15 分钟）的地方断开，不跨空白连线。
    static func segments(_ points: [GlucoseSample]) -> [[GlucoseSample]] {
        points.reduce(into: [[GlucoseSample]]()) { result, point in
            if let last = result.last?.last, point.time.timeIntervalSince(last.time) <= maxGap {
                result[result.count - 1].append(point)
            } else {
                result.append([point])
            }
        }
    }

    /// 把一段折线在穿过上下限的位置切开（插入交点），每段一种颜色。
    static func coloredRuns(_ segment: [GlucoseSample], low: Double, high: Double) -> [ColoredRun] {
        guard let first = segment.first else { return [] }
        var runs: [ColoredRun] = []
        var level = GlucoseLevel.of(first.mmol, low: low, high: high)
        var current = [first]
        for (a, b) in zip(segment, segment.dropFirst()) {
            for threshold in crossedThresholds(from: a.mmol, to: b.mmol, low: low, high: high) {
                let crossing = interpolate(a, b, at: threshold)
                current.append(crossing)
                runs.append(ColoredRun(level: level, points: current))
                let mid = (b.mmol > a.mmol) ? threshold + 1e-9 : threshold - 1e-9
                level = GlucoseLevel.of(mid, low: low, high: high)
                current = [crossing]
            }
            current.append(b)
        }
        runs.append(ColoredRun(level: level, points: current))
        return runs
    }

    private static func crossedThresholds(from a: Double, to b: Double, low: Double, high: Double) -> [Double] {
        let crossed = [low, high].filter { t in
            // 严格穿过：一端在阈值一侧、另一端越过阈值（按 classify 的 < low / > high 规则）
            (t == low && ((a >= low && b < low) || (a < low && b >= low))) ||
            (t == high && ((a <= high && b > high) || (a > high && b <= high)))
        }
        return b > a ? crossed.sorted() : crossed.sorted(by: >)
    }

    private static func interpolate(_ a: GlucoseSample, _ b: GlucoseSample, at value: Double) -> GlucoseSample {
        let fraction = b.mmol == a.mmol ? 0 : (value - a.mmol) / (b.mmol - a.mmol)
        let time = a.time.addingTimeInterval(b.time.timeIntervalSince(a.time) * fraction)
        return GlucoseSample(time: time, mmol: value)
    }
}

/// 光标指向的时间，以及该处今日 / 昨日的读数。
struct CursorReadout: Equatable {
    let time: Date
    let today: GlucoseSample?
    let yesterday: GlucoseSample?
}

/// 把鼠标所在时间映射到读数：吸附到 10 分钟内最近的今日读数，没有则吸附到昨日读数。
enum ChartCursor {
    static let snap: TimeInterval = 10 * 60

    static func readout(_ data: ChartData, at requested: Date) -> CursorReadout {
        let at = min(max(requested, data.window.start), data.window.end)
        let today = nearest(data.today, to: at)
        let anchor = today?.time ?? nearest(data.yesterday, to: at)?.time ?? at
        return CursorReadout(time: anchor, today: today, yesterday: nearest(data.yesterday, to: anchor))
    }

    private static func nearest(_ points: [GlucoseSample], to at: Date) -> GlucoseSample? {
        points.min { abs($0.time.timeIntervalSince(at)) < abs($1.time.timeIntervalSince(at)) }
            .flatMap { abs($0.time.timeIntervalSince(at)) <= snap ? $0 : nil }
    }
}

/// 达标时间、最大波动、最高 / 最低血糖（读数间隔固定，读数占比近似时间占比）。
struct GlucoseStats: Equatable {
    let timeInRangePercent: Double
    let maxSwing: Double
    let highest: GlucoseSample
    let lowest: GlucoseSample

    static func compute(_ points: [GlucoseSample], low: Double, high: Double) -> GlucoseStats? {
        guard let highest = points.max(by: { $0.mmol < $1.mmol }),
              let lowest = points.min(by: { $0.mmol < $1.mmol }) else { return nil }
        let inRange = points.filter { GlucoseLevel.of($0.mmol, low: low, high: high) == .inRange }.count
        return GlucoseStats(timeInRangePercent: roundToTenth(Double(inRange) * 100 / Double(points.count)),
                            maxSwing: roundToTenth(highest.mmol - lowest.mmol),
                            highest: highest, lowest: lowest)
    }

    private static func roundToTenth(_ value: Double) -> Double { (value * 10).rounded() / 10 }
}
