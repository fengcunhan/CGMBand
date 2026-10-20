import Combine
import Foundation

/// 主窗口、菜单栏和设置窗口共用的状态。数据只来自手机推送。
@MainActor
final class CGMModel: ObservableObject {
    @Published var current: Double? = nil          // 最新读数 mmol/L；LO / HI 时为 nil
    @Published var currentRaw: String? = nil       // 非数字读数原文（LO / HI）
    @Published var trend: GlucoseTrend = .none     // 手机给出的趋势
    @Published var lastUpdate: Date? = nil         // 最新读数的测量时间（手机的 ts）；nil = 时间未知
    @Published var sensorConnected = true          // 手机报告的传感器连接状态（bg.connected）
    @Published var lastSyncAt: Date? = nil         // 最近一次收到手机 bg 的时间
    @Published var points: [GlucoseSample] = []    // 本地历史（≤ 14 天，按时间排序）
    @Published var lowLimit: Double
    @Published var highLimit: Double
    @Published var phoneState: PhoneLinkState = .stopped
    @Published var trustedPhones: [TrustedPhone] = []
    @Published var log: [String] = []              // 诊断日志，新的在前

    let history: HistoryStore
    private(set) lazy var phone = PhoneBridge(model: self)

    private static let maxLogLines = 200
    private static let logFile = "/tmp/cgm_app.log"
    private static let lowKey = "targetLow"
    private static let highKey = "targetHigh"

    init(history: HistoryStore = HistoryStore()) {
        self.history = history
        let defaults = UserDefaults.standard
        let low = defaults.double(forKey: CGMModel.lowKey)
        let high = defaults.double(forKey: CGMModel.highKey)
        let valid = low > 0 && low < high
        lowLimit = valid ? low : 3.9          // 默认与 iCan / 手机端一致
        highLimit = valid ? high : 7.8
        points = history.samples
        // 手机连上之前先显示上次的读数（菜单栏和主窗口里是灰色）
        if let last = history.samples.last {
            current = last.mmol
            lastUpdate = last.time
        }
        history.onWriteError = { [weak self] message in
            MainActor.assumeIsolated { self?.addLog(message) }
        }
    }

    func start() {
        phone.start()
        trustedPhones = phone.trustedPhones
    }

    /// 退出前：断开手机、把本地历史写盘。
    func shutdown() {
        phone.stop()
        history.flush()
    }

    func setTargetRange(low: Double, high: Double) {
        guard low != lowLimit || high != highLimit else { return }
        lowLimit = low
        highLimit = high
        UserDefaults.standard.set(low, forKey: CGMModel.lowKey)
        UserDefaults.standard.set(high, forKey: CGMModel.highKey)
    }

    func addLog(_ message: String) {
        let stamp = DateFormatter()
        stamp.dateFormat = "HH:mm:ss"
        let line = "[\(stamp.string(from: Date()))] \(message)"
        log.insert(line, at: 0)
        if log.count > CGMModel.maxLogLines { log.removeLast() }
        appendToLogFile(line)
    }

    private func appendToLogFile(_ line: String) {
        let path = CGMModel.logFile
        if !FileManager.default.fileExists(atPath: path) {
            FileManager.default.createFile(atPath: path, contents: nil)
        }
        guard let handle = FileHandle(forWritingAtPath: path) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data((line + "\n").utf8))
    }
}
