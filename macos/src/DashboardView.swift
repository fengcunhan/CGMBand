import Combine
import SwiftUI

/// 主窗口：一张卡片，只用来看血糖（与手机端首页一致）。其余功能都在设置窗口。
struct DashboardView: View {
    static let hourChoices = [4, 8, 12, 24]
    static let cardWidth: CGFloat = 480

    @ObservedObject var model: CGMModel
    let openSettings: () -> Void

    @AppStorage("chartHours") private var hours = 4
    @State private var now = Date()
    @State private var hover = ChartHover()   // 不观察：悬停只重绘图例和光标层
    private let ticker = Timer.publish(every: 20, on: .main, in: .common).autoconnect()

    var body: some View {
        let window = ChartWindow.forHours(hours, now: now)
        let data = ChartData.build(model.points, window: window)
        VStack(alignment: .leading, spacing: 0) {
            header
            valueRow.padding(.top, 4)
            HoursPicker(hours: $hours, choices: Self.hourChoices).padding(.top, 18)
            LegendRow(hover: hover).padding(.top, 16)
            GlucoseChartView(data: data, low: model.lowLimit, high: model.highLimit, hover: hover)
                .frame(height: 270)
                .padding(.top, 12)
            StatsRow(stats: GlucoseStats.compute(data.today, low: model.lowLimit, high: model.highLimit))
                .padding(.top, 18)
        }
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Palette.card))
        .padding(14)
        .frame(width: Self.cardWidth)
        .background(Palette.page)
        .onReceive(ticker) { now = $0 }
    }

    private var hasReading: Bool { model.current != nil || model.currentRaw != nil }

    private var header: some View {
        HStack {
            Text(GlucoseDisplay.readingTimeLabel(measuredAt: model.lastUpdate, hasReading: hasReading,
                                                 now: max(now, Date())))
                .font(.system(size: 17))
                .foregroundStyle(Palette.textPrimary)
            Spacer()
            Button(action: openSettings) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 19))
                    .foregroundStyle(Palette.textPrimary)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("设置")
        }
    }

    private var valueRow: some View {
        let display = model.display
        let value = model.current.map(GlucoseDisplay.format) ?? model.currentRaw ?? "--"
        return HStack(alignment: .bottom, spacing: 0) {
            Text(value)
                .font(.system(size: 76, weight: .bold))
                .tracking(-2)
                .foregroundStyle(Palette.tone(display.tone))
                .lineLimit(1)
                .fixedSize()
            VStack(alignment: .leading, spacing: 2) {
                Text(hasReading ? model.trend.arrow : "")
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(Palette.textPrimary)
                Text("mmol/L")
                    .font(.system(size: 17))
                    .foregroundStyle(Palette.textPrimary)
            }
            .padding(.leading, 10)
            .padding(.bottom, 12)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 6) {
                Chip(text: model.phoneChipText, color: Palette.textSecondary)
                Chip(text: model.sensorChipText, color: model.sensorConnected ? Palette.textSecondary : Palette.low)
            }
            .padding(.bottom, 14)
        }
    }
}

private struct Chip: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Palette.chip))
    }
}

/// 4 / 8 / 12 / 24 小时：灰色圆角轨道，选中项是白色药丸、粗体字。
private struct HoursPicker: View {
    @Binding var hours: Int
    let choices: [Int]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(choices, id: \.self) { choice in
                let selected = choice == hours
                Button { hours = choice } label: {
                    Text("\(choice)小时")
                        .font(.system(size: 15, weight: selected ? .semibold : .regular))
                        .foregroundStyle(selected ? Palette.textPrimary : Palette.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 9, style: .continuous)
                                    .fill(Palette.segmentSelected)
                                    .shadow(color: .black.opacity(0.08), radius: 1.5, y: 1)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.segmentTrack))
    }
}

/// 「— 今日  — 昨日」；悬停曲线时变成「14:27  — 今日 6.3  — 昨日 5.9」。
private struct LegendRow: View {
    @ObservedObject var hover: ChartHover

    var body: some View {
        let readout = hover.readout
        HStack(spacing: 6) {
            if let readout {
                Text(GlucoseDisplay.clock(readout.time))
                    .font(.system(size: 15)).foregroundStyle(Palette.textSecondary)
                    .padding(.trailing, 10)
            }
            swatch(Palette.today)
            label("今日")
            if let readout { value(readout.today) }
            swatch(Palette.yesterday).padding(.leading, 14)
            label("昨日")
            if let readout { value(readout.yesterday) }
        }
        .frame(height: 24)
    }

    private func swatch(_ color: Color) -> some View {
        RoundedRectangle(cornerRadius: 1.5).fill(color).frame(width: 18, height: 3)
    }

    private func label(_ text: String) -> some View {
        Text(text).font(.system(size: 14)).foregroundStyle(Palette.textPrimary)
    }

    private func value(_ sample: GlucoseSample?) -> some View {
        Text(sample.map { GlucoseDisplay.format($0.mmol) } ?? "--")
            .font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.textPrimary)
    }
}

/// 达标时间 / 最大波动 / 最高血糖 09:45 / 最低血糖 11:36：大号数字 + 小号单位。
private struct StatsRow: View {
    let stats: GlucoseStats?
    private static let widths: [CGFloat] = [80, 92, 120, 120]   // 合计 = 卡片内宽 412

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            column(0, "达标时间", stats.map { String(format: "%.1f", $0.timeInRangePercent) }, unit: "%")
            column(1, "最大波动", stats.map { GlucoseDisplay.format($0.maxSwing) }, unit: "mmol/L")
            column(2, "最高血糖" + (stats.map { " " + GlucoseDisplay.clock($0.highest.time) } ?? ""),
                   stats.map { GlucoseDisplay.format($0.highest.mmol) }, unit: "mmol/L")
            column(3, "最低血糖" + (stats.map { " " + GlucoseDisplay.clock($0.lowest.time) } ?? ""),
                   stats.map { GlucoseDisplay.format($0.lowest.mmol) }, unit: "mmol/L")
        }
    }

    private func column(_ index: Int, _ title: String, _ number: String?, unit: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 12)).foregroundStyle(Palette.textSecondary).lineLimit(1)
            Group {
                if let number {
                    Text(number).font(.system(size: 20, weight: .bold))
                        + Text(" \(unit)").font(.system(size: 12))
                } else {
                    Text("--").font(.system(size: 20, weight: .bold))
                }
            }
            .foregroundStyle(Palette.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .frame(width: Self.widths[index], alignment: .leading)
    }
}
