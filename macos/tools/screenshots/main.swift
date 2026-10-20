// Renders the README screenshots of the Mac app from synthetic demo data, so no real glucose
// readings are published. Uses the app's own views; nothing is launched, no network, and the
// history store is in-memory. Run via tools/render_screenshots.sh.
import AppKit
import SwiftUI

// MARK: - Demo data (same model as the phone's DemoData: flat night, meal peaks, wobble)

private struct Meal {
    let startMinute: Double
    let today: Double
    let yesterday: Double
}

private let meals = [
    Meal(startMinute: 7 * 60 + 30, today: 2.6, yesterday: 2.1),
    Meal(startMinute: 12 * 60 + 10, today: 3.3, yesterday: 2.4),
    Meal(startMinute: 18 * 60 + 40, today: 2.2, yesterday: 3.0),
]

private func mealResponse(_ sinceMeal: Double, _ peak: Double) -> Double {
    if sinceMeal < 0 || sinceMeal > 240 { return 0 }
    if sinceMeal < 50 { return peak * sinceMeal / 50 }
    return peak * exp(-(sinceMeal - 50) / 55)
}

private func demoSamples(now: Date) -> [GlucoseSample] {
    let cadence: TimeInterval = 180
    let end = floor(now.timeIntervalSince1970 / cadence) * cadence
    let calendar = Calendar.current
    let today = calendar.startOfDay(for: now)
    return stride(from: end - 49 * 3600, through: end, by: cadence).map { t in
        let date = Date(timeIntervalSince1970: t)
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let minuteOfDay = Double(parts.hour! * 60 + parts.minute!)
        let isToday = calendar.startOfDay(for: date) == today
        let food = meals.reduce(0) { $0 + mealResponse(minuteOfDay - $1.startMinute, isToday ? $1.today : $1.yesterday) }
        let minutes = t / 60
        let wobble = 0.22 * sin(minutes / 37) + 0.12 * sin(minutes / 11 + 1.3)
        let dawn = 0.4 * exp(-pow((minuteOfDay - 300) / 70, 2))
        let mmol = min(max(5.6 + food + wobble + dawn, 3.4), 12.5)
        return GlucoseSample(time: date, mmol: (mmol * 10).rounded() / 10)
    }
}

/// Same arrows as the phone: 1 / 2 / 3 mg/dL per minute between the last two readings.
private func trend(_ samples: [GlucoseSample]) -> GlucoseTrend {
    guard samples.count >= 2 else { return .none }
    let a = samples[samples.count - 2], b = samples[samples.count - 1]
    let rate = (b.mmol - a.mmol) * 18 / (b.time.timeIntervalSince(a.time) / 60)
    let name: String
    switch abs(rate) {
    case ..<1: name = "FLAT"
    case ..<2: name = rate > 0 ? "FORTY_FIVE_UP" : "FORTY_FIVE_DOWN"
    case ..<3: name = rate > 0 ? "SINGLE_UP" : "SINGLE_DOWN"
    default: name = rate > 0 ? "DOUBLE_UP" : "DOUBLE_DOWN"
    }
    return GlucoseTrend(rawValue: name) ?? .none
}

// MARK: - Rendering

@MainActor
private func demoModel(now: Date) -> CGMModel {
    let model = CGMModel(history: HistoryStore(fileURL: nil))   // in-memory: never touches the real history
    let samples = demoSamples(now: now)
    model.points = samples
    model.current = samples.last?.mmol
    model.lastUpdate = samples.last?.time
    model.lastSyncAt = samples.last?.time
    model.trend = trend(samples)
    model.sensorConnected = true
    model.lowLimit = 3.9
    model.highLimit = 7.8
    model.phoneState = .connected(PhoneInfo(id: "demo", name: "Xiaomi 13 Ultra"))
    return model
}

@MainActor
private func writePNG<V: View>(_ view: V, dark: Bool, to path: String) {
    let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)!
    var png: Data?
    appearance.performAsCurrentDrawingAppearance {
        let renderer = ImageRenderer(content: view.environment(\.colorScheme, dark ? .dark : .light))
        renderer.scale = 2
        if let image = renderer.cgImage {
            png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])
        }
    }
    guard let data = png else { fatalError("failed to render \(path)") }
    do {
        try data.write(to: URL(fileURLWithPath: path))
        print("wrote \(path)")
    } catch {
        fatalError("failed to write \(path): \(error)")
    }
}

/// The menu-bar title exactly as the app draws it, on a menu-bar coloured strip.
@MainActor
private func menuBarStrip(_ model: CGMModel, dark: Bool) -> some View {
    let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)!
    let display = model.display
    let title = StatusTitleImage.make(display.title, tone: display.tone, appearance: appearance)
    return HStack {
        Spacer()
        Image(nsImage: title)
    }
    .padding(.horizontal, 14)
    .frame(width: 220, height: 30)
    .background(dark ? Color(white: 0.16) : Color(white: 0.93))
    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
}

let outDir = CommandLine.arguments.dropFirst().first ?? "."

MainActor.assumeIsolated {
    let model = demoModel(now: Date())
    for (hours, suffix) in [(4, "4h"), (24, "24h")] {
        UserDefaults.standard.register(defaults: ["chartHours": hours])   // volatile; no prefs written
        let window = DashboardView(model: model, openSettings: {})
        writePNG(window, dark: false, to: "\(outDir)/mac-dashboard-\(suffix).png")
        if hours == 4 { writePNG(window, dark: true, to: "\(outDir)/mac-dashboard-dark.png") }
    }
    // Light and dark menu bars stacked: each title image is already resolved for its appearance.
    let menuBars = VStack(spacing: 10) {
        menuBarStrip(model, dark: false)
        menuBarStrip(model, dark: true)
    }
    writePNG(menuBars, dark: false, to: "\(outDir)/mac-menubar.png")
}
