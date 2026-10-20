import Charts
import SwiftUI

/// 鼠标悬停时光标所指的读数。由图例和光标层观察，曲线本身不随鼠标重绘。
final class ChartHover: ObservableObject {
    @Published var readout: CursorReadout?
}

/// 血糖曲线：灰色昨日线，今日线在上限以上变橙、下限以下变红，目标带着色，上下限标在右侧。
struct GlucoseChartView: View {
    let data: ChartData
    let low: Double
    let high: Double
    let hover: ChartHover

    private enum Stroke { case yesterday, low, inRange, high }

    private struct LinePoint: Identifiable {
        let id: Int
        let series: String
        let sample: GlucoseSample
        let stroke: Stroke
    }

    var body: some View {
        let lines = linePoints()
        let dots = singlePoints()
        Chart {
            RectangleMark(xStart: .value("开始", data.window.start), xEnd: .value("结束", data.window.end),
                          yStart: .value("下限", low), yEnd: .value("上限", high))
                .foregroundStyle(Palette.band)
            ForEach(lines) { p in
                LineMark(x: .value("时间", p.sample.time), y: .value("血糖", p.sample.mmol),
                         series: .value("段", p.series))
                    .foregroundStyle(color(p.stroke))
                    .lineStyle(StrokeStyle(lineWidth: p.stroke == .yesterday ? 2.5 : 3, lineCap: .round, lineJoin: .round))
            }
            ForEach(dots) { p in
                PointMark(x: .value("时间", p.sample.time), y: .value("血糖", p.sample.mmol))
                    .foregroundStyle(color(p.stroke))
                    .symbolSize(40)
            }
        }
        .chartXScale(domain: data.window.start...data.window.end)
        .chartYScale(domain: 0...data.yMax)
        .chartXAxis { timeAxis }
        .chartYAxis { valueAxis }
        .chartLegend(.hidden)
        .chartOverlay { proxy in
            GeometryReader { geo in
                CursorLayer(proxy: proxy, plot: proxy.plotFrame.map { geo[$0] } ?? .zero, data: data, hover: hover)
            }
        }
        .overlay {
            if data.today.isEmpty && data.yesterday.isEmpty {
                Text("暂无数据，曲线会随新读数逐步累积")
                    .font(.system(size: 14)).foregroundStyle(Palette.textSecondary)
            }
        }
    }

    private var timeAxis: some AxisContent {
        AxisMarks(values: data.window.ticks) { value in
            AxisValueLabel(anchor: labelAnchor(value.index, of: value.count), collisionResolution: .disabled) {
                if let date = value.as(Date.self) {
                    Text(GlucoseDisplay.clock(date)).font(.system(size: 12)).foregroundStyle(Palette.textSecondary)
                }
            }
        }
    }

    private var gridValues: [Double] { Array(stride(from: 0.0, through: data.yMax, by: ChartData.yStep)) }

    @AxisContentBuilder
    private var valueAxis: some AxisContent {
        AxisMarks(position: .leading, values: gridValues) { value in
            AxisGridLine(stroke: StrokeStyle(lineWidth: 1)).foregroundStyle(Palette.grid)
            AxisValueLabel {
                if let v = value.as(Double.self) {
                    Text("\(Int(v))").font(.system(size: 12)).foregroundStyle(Palette.textSecondary)
                }
            }
        }
        AxisMarks(position: .trailing, values: [high]) { _ in
            AxisValueLabel {
                Text(GlucoseDisplay.format(high)).font(.system(size: 13)).foregroundStyle(Palette.high)
            }
        }
        AxisMarks(position: .trailing, values: [low]) { _ in
            AxisValueLabel {
                Text(GlucoseDisplay.format(low)).font(.system(size: 13)).foregroundStyle(Palette.low)
            }
        }
    }

    /// 首尾刻度标签向内对齐，避免被裁掉。
    private func labelAnchor(_ index: Int, of count: Int) -> UnitPoint {
        if index == 0 { return .topLeading }
        if index == count - 1 { return .topTrailing }
        return .top
    }

    private func color(_ stroke: Stroke) -> Color {
        switch stroke {
        case .yesterday: return Palette.yesterday
        case .low: return Palette.low
        case .inRange: return Palette.today
        case .high: return Palette.high
        }
    }

    private func stroke(_ level: GlucoseLevel) -> Stroke {
        switch level {
        case .low: return .low
        case .inRange: return .inRange
        case .high: return .high
        }
    }

    /// 每段（> 15 分钟的空白处断开）一条折线；今日线再按上下限切成不同颜色的小段。
    private func linePoints() -> [LinePoint] {
        var result: [LinePoint] = []
        func add(_ samples: [GlucoseSample], series: String, stroke: Stroke) {
            for sample in samples {
                result.append(LinePoint(id: result.count, series: series, sample: sample, stroke: stroke))
            }
        }
        for (i, segment) in ChartData.segments(data.yesterday).enumerated() where segment.count > 1 {
            add(segment, series: "y\(i)", stroke: .yesterday)
        }
        for (i, segment) in ChartData.segments(data.today).enumerated() where segment.count > 1 {
            for (j, run) in ChartData.coloredRuns(segment, low: low, high: high).enumerated() {
                add(run.points, series: "t\(i)-\(j)", stroke: stroke(run.level))
            }
        }
        return result
    }

    /// 前后都缺数据的孤立读数画成圆点。
    private func singlePoints() -> [LinePoint] {
        let yesterday = ChartData.segments(data.yesterday).filter { $0.count == 1 }.map { ($0[0], Stroke.yesterday) }
        let today = ChartData.segments(data.today).filter { $0.count == 1 }
            .map { ($0[0], stroke(GlucoseLevel.of($0[0].mmol, low: low, high: high))) }
        return (yesterday + today).enumerated().map { i, item in
            LinePoint(id: i, series: "p\(i)", sample: item.0, stroke: item.1)
        }
    }
}

/// 悬停光标：竖线 + 今日读数的空心圆点 + 昨日读数的小灰点。只有这一层随鼠标重绘。
private struct CursorLayer: View {
    let proxy: ChartProxy
    let plot: CGRect
    let data: ChartData
    @ObservedObject var hover: ChartHover

    var body: some View {
        ZStack(alignment: .topLeading) {
            Rectangle().fill(Color.clear).contentShape(Rectangle())
                .onContinuousHover { phase in
                    switch phase {
                    case let .active(location): track(location)
                    case .ended: hover.readout = nil
                    }
                }
            if let readout = hover.readout, let x = proxy.position(forX: readout.time) {
                Path { path in
                    path.move(to: CGPoint(x: plot.minX + x, y: plot.minY))
                    path.addLine(to: CGPoint(x: plot.minX + x, y: plot.maxY))
                }
                .stroke(Palette.textSecondary, lineWidth: 1)
                .allowsHitTesting(false)
                if let y = readout.yesterday.flatMap({ proxy.position(forY: $0.mmol) }) {
                    Circle().fill(Palette.yesterday).frame(width: 7, height: 7)
                        .position(x: plot.minX + x, y: plot.minY + y).allowsHitTesting(false)
                }
                if let y = readout.today.flatMap({ proxy.position(forY: $0.mmol) }) {
                    Circle().fill(Palette.card).overlay(Circle().stroke(Palette.today, lineWidth: 2.5))
                        .frame(width: 10, height: 10)
                        .position(x: plot.minX + x, y: plot.minY + y).allowsHitTesting(false)
                }
            }
        }
    }

    private func track(_ location: CGPoint) {
        guard plot.width > 0, location.x >= plot.minX - 8, location.x <= plot.maxX + 8,
              let date: Date = proxy.value(atX: min(max(location.x, plot.minX), plot.maxX) - plot.minX)
        else { return hover.readout = nil }
        hover.readout = ChartCursor.readout(data, at: date)
    }
}
