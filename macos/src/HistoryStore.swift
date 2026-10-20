import Foundation

/// 本地历史的合并规则（纯函数）。
enum HistoryMerge {
    static let retention: TimeInterval = 14 * 24 * 3600
    /// 手机的历史按「距 ref 的整分钟」发送并可能抽稀，同一读数在不同批次里的时间会差几十秒。
    static let tolerance: TimeInterval = 30
    /// 比现在还晚这么多的读数视为时钟错误，丢弃。
    static let futureSlack: TimeInterval = 3600

    /// 新的一批读数覆盖它覆盖的时间段（首尾各放宽 30 秒），时间段之外的旧读数保留；只保留最近 14 天。
    static func merge(_ existing: [GlucoseSample], with batch: [GlucoseSample], now: Date) -> [GlucoseSample] {
        let incoming = batch
            .filter { $0.mmol.isFinite && $0.time.timeIntervalSince(now) <= futureSlack }
            .sorted { $0.time < $1.time }
        let cutoff = now.addingTimeInterval(-retention)
        guard let first = incoming.first, let last = incoming.last else {
            return existing.filter { $0.time >= cutoff }
        }
        let lower = first.time.addingTimeInterval(-tolerance)
        let upper = last.time.addingTimeInterval(tolerance)
        let kept = existing.filter { $0.time < lower || $0.time > upper }
        return (kept + incoming).filter { $0.time >= cutoff }.sorted { $0.time < $1.time }
    }
}

/// 收到的每个读数都存到本地（~/Library/Application Support/CGMMonitor/history.json，0600），
/// 这样 8/12/24 小时和昨日曲线不受手机每次只发 4 小时的限制。只在主线程使用。
final class HistoryStore {
    private struct Stored: Codable {
        let version: Int
        let points: [[Double]]   // [秒级时间戳, mmol/L]
    }

    static var defaultFile: URL {
        DesktopIdentity.defaultDirectory.appendingPathComponent("history.json")
    }

    private(set) var samples: [GlucoseSample]
    /// 写盘失败时在主线程回调（写进诊断日志）。
    var onWriteError: ((String) -> Void)?
    private let fileURL: URL?
    private let saveDelay: TimeInterval
    private var pendingSave: DispatchWorkItem?
    private let writer = DispatchQueue(label: "local.cgm.monitor.history", qos: .utility)

    /// fileURL 为 nil 时只在内存里保存（测试用）。
    init(fileURL: URL? = HistoryStore.defaultFile, saveDelay: TimeInterval = 5, now: Date = Date()) {
        self.fileURL = fileURL
        self.saveDelay = saveDelay
        self.samples = HistoryMerge.merge(fileURL.map(HistoryStore.load) ?? [], with: [], now: now)
    }

    func merge(_ batch: [GlucoseSample], now: Date = Date()) {
        samples = HistoryMerge.merge(samples, with: batch, now: now)
        scheduleSave()
    }

    /// 立即写盘（退出时调用）。
    func flush() {
        pendingSave?.cancel()
        pendingSave = nil
        guard let fileURL, let data = encoded() else { return }
        if let error = writer.sync(execute: { HistoryStore.write(data, to: fileURL) }) { onWriteError?(error) }
    }

    private func scheduleSave() {
        guard fileURL != nil else { return }
        pendingSave?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, let fileURL = self.fileURL, let data = self.encoded() else { return }
            self.writer.async {
                guard let error = HistoryStore.write(data, to: fileURL) else { return }
                DispatchQueue.main.async { [weak self] in self?.onWriteError?(error) }
            }
        }
        pendingSave = work
        DispatchQueue.main.asyncAfter(deadline: .now() + saveDelay, execute: work)
    }

    private func encoded() -> Data? {
        let points = samples.map { [($0.time.timeIntervalSince1970).rounded(), ($0.mmol * 100).rounded() / 100] }
        return try? JSONEncoder().encode(Stored(version: 1, points: points))
    }

    private static func load(_ url: URL) -> [GlucoseSample] {
        guard let data = try? Data(contentsOf: url),
              let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return [] }
        return stored.points.compactMap { p in
            guard p.count == 2, p[0].isFinite, p[1].isFinite, PhoneReading.plausibleRange.contains(p[1]) else { return nil }
            return GlucoseSample(time: Date(timeIntervalSince1970: p[0]), mmol: p[1])
        }
    }

    /// 返回 nil 表示成功，否则是错误说明。写失败不影响显示，下次合并时会再写。
    private static func write(_ data: Data, to url: URL) -> String? {
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            try data.write(to: url, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            return nil
        } catch {
            return "保存本地历史失败：\(error.localizedDescription)"
        }
    }
}
