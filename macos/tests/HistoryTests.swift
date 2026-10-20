import Foundation

func runHistoryTests(_ t: TestRun) {
    t.section("本地历史：合并与保留")
    let now = Date(timeIntervalSince1970: 1_792_400_000)
    func s(_ minutesAgo: Double, _ mmol: Double) -> GlucoseSample {
        GlucoseSample(time: now.addingTimeInterval(-minutesAgo * 60), mmol: mmol)
    }
    let first = HistoryMerge.merge([], with: [s(240, 6.0), s(120, 6.5), s(3, 7.0)], now: now)
    t.equal(first.map(\.mmol), [6.0, 6.5, 7.0], "第一批原样保存并按时间排序")

    // 下一批：同一读数的时间因整分钟取整差了 20 秒，并多了新点；4 小时之外的旧点保留
    let older = [s(600, 5.0)] + first
    let shifted = [GlucoseSample(time: now.addingTimeInterval(-120 * 60 + 20), mmol: 6.5), s(1, 7.2)]
    let merged = HistoryMerge.merge(older, with: [s(240, 6.0)] + shifted, now: now)
    t.equal(merged.map(\.mmol), [5.0, 6.0, 6.5, 7.2], "新批次覆盖它的时间段：不出现重复点，旧点被替换")
    t.check(merged.first?.time == now.addingTimeInterval(-600 * 60), "批次时间段之外的旧读数保留")

    let single = HistoryMerge.merge(merged, with: [GlucoseSample(time: now.addingTimeInterval(-60 + 10), mmol: 7.3)], now: now)
    t.equal(single.map(\.mmol), [5.0, 6.0, 6.5, 7.3], "单条 bg：替换 30 秒内的同一读数")

    let day: Double = 24 * 60
    let retained = HistoryMerge.merge([s(15 * day, 4.4), s(13 * day, 5.5)], with: [s(0, 6.6)], now: now)
    t.equal(retained.map(\.mmol), [5.5, 6.6], "只保留 14 天")
    let future = HistoryMerge.merge([], with: [s(-120, 9.9), s(0, 6.6)], now: now)
    t.equal(future.map(\.mmol), [6.6], "比现在晚 1 小时以上的读数（时钟错误）被丢弃")
    t.equal(HistoryMerge.merge(merged, with: [], now: now), merged, "空批次不改动历史")

    t.section("本地历史：读写文件")
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cgm-history-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    let file = dir.appendingPathComponent("history.json")
    let store = HistoryStore(fileURL: file, saveDelay: 60, now: now)
    t.check(store.samples.isEmpty, "没有文件时为空")
    store.merge([s(30, 6.1), s(0, 6.4)], now: now)
    store.flush()
    let reloaded = HistoryStore(fileURL: file, now: now)
    t.equal(reloaded.samples.map(\.mmol), [6.1, 6.4], "写盘后重新读取一致")
    let perm = (try? FileManager.default.attributesOfItem(atPath: file.path)[.posixPermissions] as? Int) ?? -1
    t.equal(perm, 0o600, "history.json 权限 0600")
    let later = HistoryStore(fileURL: file, now: now.addingTimeInterval(14 * 24 * 3600 - 600))
    t.equal(later.samples.map(\.mmol), [6.4], "读取时也按 14 天裁剪")
    try? Data("garbage".utf8).write(to: file)
    t.check(HistoryStore(fileURL: file, now: now).samples.isEmpty, "文件损坏时当作空历史，不崩溃")
}
