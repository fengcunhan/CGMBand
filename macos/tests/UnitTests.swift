import CryptoKit
import Foundation

func runIdentityTests(_ t: TestRun) {
    t.section("本机身份与信任列表")
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cgm-identity-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    guard let first = try? DesktopIdentity.loadOrCreate(in: dir),
          let second = try? DesktopIdentity.loadOrCreate(in: dir)
    else { return t.check(false, "身份可以生成并读取") }
    t.check(PeerHello.isDeviceId(first.deviceId), "设备 ID 是 32 位十六进制")
    t.equal(second.deviceId, first.deviceId, "重新读取设备 ID 不变")
    t.equal(second.publicKey, first.publicKey, "重新读取公钥不变")
    t.equal(first.publicKey.count, 65, "公钥 65 字节 X9.63")
    let file = dir.appendingPathComponent(DesktopIdentity.fileName).path
    let filePerm = (try? FileManager.default.attributesOfItem(atPath: file)[.posixPermissions] as? Int) ?? -1
    let dirPerm = (try? FileManager.default.attributesOfItem(atPath: dir.path)[.posixPermissions] as? Int) ?? -1
    t.equal(filePerm, 0o600, "identity.json 权限 0600")
    t.equal(dirPerm, 0o700, "目录权限 0700")

    let suite = testDefaultsSuite
    guard let defaults = freshTestDefaults() else { return t.check(false, "UserDefaults suite") }
    defer { defaults.removePersistentDomain(forName: suite) }
    let store = TrustStore(defaults: defaults)
    let key = P256.KeyAgreement.PrivateKey().publicKey.x963Representation
    let id = DesktopCrypto.randomBytes(16).hexString
    t.check(!store.isTrusted(id: id, publicKey: key), "未保存时不信任")
    store.save(TrustedPhone(id: id, publicKey: key, name: "Pixel"))
    t.check(store.isTrusted(id: id, publicKey: key), "保存后信任")
    t.check(!store.isTrusted(id: id, publicKey: P256.KeyAgreement.PrivateKey().publicKey.x963Representation),
            "同一 id 换了公钥就不信任")
    store.rename(id: id, publicKey: key, to: "Pixel 9")
    t.equal(store.all().map(\.name), ["Pixel 9"], "改名后更新显示名")
    store.remove(id: id)
    t.check(store.all().isEmpty, "取消配对后列表为空")
}

func runPayloadTests(_ t: TestRun) {
    t.section("消息解析")
    let bgJSON = #"{"type":"bg","value":"6.6","unit":"mmol/L","mmol":6.6,"time":"16:33","ts":1792398000000,"trend":"SINGLE_UP","delta":0.1,"connected":true}"#
    let bg = Wire.decode(PhoneReading.self, from: Data(bgJSON.utf8))
    t.equal(bg?.glucose, 6.6, "bg.mmol")
    t.equal(bg?.measuredAt, Date(timeIntervalSince1970: 1_792_398_000), "bg.ts → 测量时间")
    t.equal(bg?.trendValue, .singleUp, "bg.trend")
    let lo = Wire.decode(PhoneReading.self, from: Data(#"{"type":"bg","value":"LO","mmol":null,"ts":null,"trend":"NONE","delta":null,"connected":false}"#.utf8))
    t.check(lo != nil && lo?.glucose == nil && lo?.measuredAt == nil, "LO：mmol/ts 为 null")
    t.equal(lo?.displayText, "LO", "LO 原文")
    t.equal(lo?.sensorConnected, false, "connected=false")
    t.check(Wire.decode(PhoneReading.self, from: Data(#"{"type":"bg","ts":"abc"}"#.utf8)) == nil, "ts 类型错误 → 解析失败")
    t.check(Wire.decode(PhoneReading.self, from: Data("not json".utf8)) == nil, "非 JSON → 解析失败")

    let historyJSON = #"{"type":"history","ref":1792398000000,"range":[3.9,7.8],"points":[[240,66],[237,65],[3,62],[0,61]]}"#
    let history = Wire.decode(PhoneHistory.self, from: Data(historyJSON.utf8))
    let samples = history?.samples ?? []
    let ref = Date(timeIntervalSince1970: 1_792_398_000)
    t.equal(samples.count, 4, "history 点数")
    t.equal(samples.first, GlucoseSample(time: ref.addingTimeInterval(-240 * 60), mmol: 6.6), "最早点 = ref − 240 分钟, 6.6")
    t.equal(samples.last, GlucoseSample(time: ref, mmol: 6.1), "最新点 = ref, 6.1")
    t.check(history?.targetRange?.low == 3.9 && history?.targetRange?.high == 7.8, "range → 目标范围")
    let empty = Wire.decode(PhoneHistory.self, from: Data(#"{"type":"history","ref":null,"range":[3.9,10],"points":[]}"#.utf8))
    t.check(empty != nil && empty?.samples.isEmpty == true, "ref 为 null → 空历史")
    let bad = Wire.decode(PhoneHistory.self, from: Data(#"{"type":"history","ref":1,"range":[9,3],"points":[[1],[2,-5],[3,70]]}"#.utf8))
    t.check(bad?.targetRange == nil && bad?.samples.count == 1, "无效 range 与无效点被忽略")
}

func runDisplayTests(_ t: TestRun) {
    t.section("菜单栏显示")
    let now = Date()
    func snap(_ mmol: Double?, raw: String? = nil, trend: GlucoseTrend = .flat, age: TimeInterval? = 180,
              link: String? = nil, sensor: Bool = true) -> GlucoseDisplay {
        GlucoseDisplay(GlucoseSnapshot(mmol: mmol, rawText: raw, trend: trend,
                                       measuredAt: age.map { now.addingTimeInterval(-$0) },
                                       lowLimit: 3.9, highLimit: 7.8, linkProblem: link, sensorConnected: sensor),
                       now: now)
    }
    let normal = snap(6.3)
    t.equal(normal.title, "6.3 →", "标题")
    t.equal(normal.tone, .inRange, "范围内 → 绿色")
    t.equal(normal.header, "6.3 mmol/L → · 3分钟前", "菜单首行")
    t.equal(snap(9.1, trend: .doubleUp).tone, .high, "高于上限 → 橙色")
    t.equal(snap(9.1, trend: .doubleUp).title, "9.1 ⇈", "双箭头")
    t.equal(snap(3.2, trend: .singleDown).tone, .low, "低于下限 → 红色")
    t.equal(snap(nil).title, "--", "无数据 → --")
    t.equal(snap(nil).tone, .none, "无数据不着色")
    let old = snap(6.3, age: 25 * 60)
    t.equal(old.tone, .stale, "超过 10 分钟 → 灰色")
    t.equal(old.title, "6.3 →", "过期仍显示数值")
    t.equal(old.header, "6.3 mmol/L → · 25分钟前，已过期", "过期原因")
    t.equal(snap(6.3, age: nil).staleReason, "测量时间未知", "ts 为 null → 过期")
    t.equal(snap(6.3, link: "手机未连接").header, "6.3 mmol/L → · 手机未连接", "手机断开 → 过期")
    t.equal(snap(6.3, sensor: false).staleReason, "传感器未连接", "传感器断开 → 过期")
    let low = snap(nil, raw: "LO", trend: .none)
    t.equal(low.title, "LO", "LO 无箭头")
    t.equal(low.tone, .low, "LO → 红色")
    t.equal(GlucoseDisplay.age(of: now.addingTimeInterval(-125 * 60), now: now), "2小时前", "相对时间（< 1 天按小时）")
}
