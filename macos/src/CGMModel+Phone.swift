import Foundation

extension PhoneLinkState {
    var description: String {
        switch self {
        case .stopped: return "未接收手机推送"
        case .listening: return "等待手机连接…"
        case let .pairing(phone): return "正在与「\(phone.name)」配对…"
        case let .connected(phone): return "手机：\(phone.name) 已连接"
        case let .failed(message): return message
        }
    }
}

extension TrustedPhone: Identifiable {}

extension CGMModel {
    // MARK: - 手机推送的数据

    func applyPhoneReading(_ reading: PhoneReading) {
        lastSyncAt = Date()
        let measured = reading.measuredAt
        if let measured, let last = lastUpdate, measured < last {
            return addLog("忽略较旧的读数")
        }
        current = reading.glucose
        currentRaw = reading.glucose == nil ? reading.displayText : nil
        trend = reading.trendValue
        lastUpdate = measured
        sensorConnected = reading.sensorConnected
        if let glucose = reading.glucose, let measured {
            history.merge([GlucoseSample(time: measured, mmol: glucose)])
            points = history.samples
        }
        let text = reading.glucose.map(GlucoseDisplay.format) ?? reading.displayText ?? "?"
        let sensor = reading.sensorConnected ? "" : "（传感器未连接）"
        addLog("手机推送 \(text) \(reading.trendValue.arrow) \(measured.map { GlucoseDisplay.age(of: $0) } ?? "时间未知")\(sensor)")
    }

    func applyPhoneHistory(_ phoneHistory: PhoneHistory) {
        let samples = phoneHistory.samples
        if !samples.isEmpty {
            history.merge(samples)
            points = history.samples
        }
        if let range = phoneHistory.targetRange {
            setTargetRange(low: range.low, high: range.high)
        }
    }

    func unpairPhone(id: String) { phone.unpair(id: id) }

    // MARK: - 显示

    /// 连接状态一行字（设置窗口与菜单栏菜单）。
    var connectionText: String {
        let sensor = phoneState.connectedPhone != nil && !sensorConnected ? "，传感器未连接" : ""
        return phoneState.description + sensor
    }

    /// 主窗口右侧第一个标签。
    var phoneChipText: String {
        switch phoneState {
        case .connected:
            return lastSyncAt.map { "手机 已同步 \(GlucoseDisplay.clock($0))" } ?? "手机 已连接"
        case .pairing:
            return "手机 配对中"
        default:
            return "等待手机连接"
        }
    }

    var sensorChipText: String { sensorConnected ? "传感器 正常" : "传感器 未连接" }

    var targetRangeText: String {
        "\(GlucoseDisplay.format(lowLimit)) – \(GlucoseDisplay.format(highLimit)) mmol/L"
    }

    var glucoseSnapshot: GlucoseSnapshot {
        GlucoseSnapshot(
            mmol: current,
            rawText: currentRaw,
            trend: trend,
            measuredAt: lastUpdate,
            lowLimit: lowLimit,
            highLimit: highLimit,
            linkProblem: phoneState.connectedPhone == nil ? "手机未连接" : nil,
            sensorConnected: sensorConnected)
    }

    var display: GlucoseDisplay { GlucoseDisplay(glucoseSnapshot) }
}
