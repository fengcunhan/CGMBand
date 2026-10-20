import Foundation

private func shanghaiCalendar() -> Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Shanghai") ?? .current
    return calendar
}

private func at(_ calendar: Calendar, _ day: Int, _ hour: Int, _ minute: Int, _ second: Int = 0) -> Date {
    calendar.date(from: DateComponents(year: 2026, month: 10, day: day, hour: hour, minute: minute, second: second)) ?? Date()
}

private func sample(_ time: Date, _ mmol: Double) -> GlucoseSample { GlucoseSample(time: time, mmol: mmol) }

func runChartTests(_ t: TestRun) {
    t.section("曲线：时间窗口与数据")
    let cal = shanghaiCalendar()
    let now = at(cal, 20, 14, 42, 10)
    let w4 = ChartWindow.forHours(4, now: now, calendar: cal)
    t.equal(w4.end, at(cal, 20, 15, 0), "窗口结束于下一个整点 15:00")
    t.equal(w4.start, at(cal, 20, 11, 0), "4 小时窗口从 11:00 开始")
    t.equal(w4.ticks, [11, 12, 13, 14, 15].map { at(cal, 20, $0, 0) }, "5 个刻度，每小时一个")
    let w24 = ChartWindow.forHours(24, now: now, calendar: cal)
    t.equal(w24.ticks, [at(cal, 19, 15, 0), at(cal, 19, 21, 0), at(cal, 20, 3, 0), at(cal, 20, 9, 0), at(cal, 20, 15, 0)],
            "24 小时窗口每 6 小时一个刻度")
    t.equal(ChartWindow.forHours(4, now: at(cal, 20, 14, 0), calendar: cal).end, at(cal, 20, 15, 0), "整点时也结束于下一个整点")

    let points = [sample(at(cal, 19, 12, 0), 5.9), sample(at(cal, 19, 9, 0), 4.0),
                  sample(at(cal, 20, 12, 0), 6.3), sample(at(cal, 20, 10, 59), 6.0), sample(at(cal, 20, 13, 0), 13.1)]
    let data = ChartData.build(points, window: w4)
    t.equal(data.today.map(\.mmol), [6.3, 13.1], "今日：只取窗口内的点并按时间排序")
    t.equal(data.yesterday, [sample(at(cal, 20, 12, 0), 5.9)], "昨日：平移 24 小时到今日时间轴")
    t.equal(data.yMax, 15, "y 轴上限 = max(12, 向上取整到 3 的倍数)")
    t.equal(ChartData.build([sample(at(cal, 20, 12, 0), 6)], window: w4).yMax, 12, "y 轴上限至少 12")

    t.section("曲线：断线与换色")
    let base = at(cal, 20, 12, 0)
    let gapped = [0, 5, 20, 36, 40].map { sample(base.addingTimeInterval(Double($0) * 60), 6) }
    t.equal(ChartData.segments(gapped).map(\.count), [3, 2], "间隔 > 15 分钟断开（15 分整不断）")
    t.equal(ChartData.segments([]).count, 0, "空序列没有段")
    let crossing = [sample(base, 6.0), sample(base.addingTimeInterval(600), 10.0), sample(base.addingTimeInterval(1200), 2.0)]
    let runs = ChartData.coloredRuns(crossing, low: 3.9, high: 7.8)
    t.equal(runs.map(\.level), [.inRange, .high, .inRange, .low], "穿过上限变橙、回落、再穿过下限变红")
    t.equal(runs[0].points.last?.mmol, 7.8, "在上限处插入交点")
    t.equal(runs[1].points.first, runs[0].points.last, "相邻两段共用交点，线不断")
    let crossAt = runs[0].points.last?.time.timeIntervalSince(base) ?? 0
    t.check(abs(crossAt - 270) < 0.001, "交点时间按线性插值（6.0→10.0 在 4.5 分钟处到 7.8）")
    t.equal(runs[3].points.last?.mmol, 2.0, "最后一段到终点")
    t.equal(ChartData.coloredRuns([sample(base, 9), sample(base.addingTimeInterval(60), 9.5)], low: 3.9, high: 7.8).map(\.level),
            [.high], "全程高于上限：一段橙色")
    t.equal(ChartData.coloredRuns([sample(base, 7.8), sample(base.addingTimeInterval(60), 6)], low: 3.9, high: 7.8).count,
            1, "正好在上限上（不高于）不算穿越")
}

func runCursorAndStatsTests(_ t: TestRun) {
    t.section("曲线：悬停读数")
    let cal = shanghaiCalendar()
    let window = ChartWindow.forHours(4, now: at(cal, 20, 14, 42), calendar: cal)
    let points = [sample(at(cal, 20, 14, 27), 6.3), sample(at(cal, 20, 13, 0), 7.0),
                  sample(at(cal, 19, 14, 25), 5.9), sample(at(cal, 19, 14, 55), 6.1)]
    let data = ChartData.build(points, window: window)
    let hit = ChartCursor.readout(data, at: at(cal, 20, 14, 30))
    t.equal(hit.time, at(cal, 20, 14, 27), "吸附到最近的今日读数")
    t.equal(hit.today?.mmol, 6.3, "今日 6.3")
    t.equal(hit.yesterday?.mmol, 5.9, "昨日取吸附时间附近的读数 5.9")
    let ahead = ChartCursor.readout(data, at: at(cal, 20, 14, 52))
    t.check(ahead.today == nil && ahead.time == at(cal, 20, 14, 55) && ahead.yesterday?.mmol == 6.1,
            "今日没有读数（在现在之后）时吸附到昨日读数")
    let empty = ChartCursor.readout(data, at: at(cal, 20, 11, 30))
    t.check(empty.today == nil && empty.yesterday == nil && empty.time == at(cal, 20, 11, 30), "10 分钟内没有读数 → 两个都是 --")
    t.equal(ChartCursor.readout(data, at: at(cal, 20, 9, 0)).time, window.start, "超出窗口的时间被夹到窗口内")

    t.section("统计")
    let stats = GlucoseStats.compute([sample(at(cal, 20, 9, 45), 8.7), sample(at(cal, 20, 10, 0), 6.0),
                                      sample(at(cal, 20, 11, 36), 5.4), sample(at(cal, 20, 12, 0), 7.8),
                                      sample(at(cal, 20, 12, 5), 3.9), sample(at(cal, 20, 12, 10), 3.8),
                                      sample(at(cal, 20, 12, 15), 7.0)], low: 3.9, high: 7.8)
    t.equal(stats?.timeInRangePercent, 71.4, "达标时间 5/7 = 71.4%（上下限本身算达标）")
    t.equal(stats?.maxSwing, 4.9, "最大波动 8.7 − 3.8")
    t.equal(stats?.highest.time, at(cal, 20, 9, 45), "最高血糖的时间")
    t.equal(stats?.lowest.mmol, 3.8, "最低血糖")
    t.check(GlucoseStats.compute([], low: 3.9, high: 7.8) == nil, "没有读数 → 无统计")

    t.section("相对时间标签")
    let now = at(cal, 20, 14, 45)
    func label(_ measured: Date?, has: Bool = true) -> String {
        GlucoseDisplay.readingTimeLabel(measuredAt: measured, hasReading: has, now: now)
    }
    t.check(label(at(cal, 20, 14, 44, 30)).hasPrefix("刚刚 · 10-20 "), "< 1 分钟：刚刚")
    t.check(label(at(cal, 20, 14, 42)).hasPrefix("3分钟前 · "), "3 分钟前（不再总是「刚刚」）")
    t.check(label(at(cal, 20, 12, 40)).hasPrefix("2小时前 · "), "< 1 天：N小时前")
    t.check(label(at(cal, 18, 14, 0)).hasPrefix("2天前 · "), "≥ 1 天：N天前")
    t.equal(label(nil), "时间未知", "ts 为 null：时间未知")
    t.equal(label(nil, has: false), "暂无数据", "还没有读数")
}
