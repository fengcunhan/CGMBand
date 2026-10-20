import AppKit
import SwiftUI

/// 设置窗口（分组表单，与手机端设置页风格一致）：手机、目标范围、诊断日志。
struct SettingsView: View {
    @ObservedObject var model: CGMModel

    var body: some View {
        Form {
            phoneSection
            Section {
                LabeledContent("目标范围") {
                    Text(model.targetRangeText).foregroundStyle(.secondary)
                }
            } header: {
                Text("目标范围")
            } footer: {
                footnote("由手机端设置：用于数值颜色、曲线目标带和达标时间。")
            }
            logSection
        }
        .formStyle(.grouped)
        .frame(width: 520, height: 620)
    }

    private var phoneSection: some View {
        Section {
            LabeledContent("连接状态") {
                Text(model.connectionText).foregroundStyle(.secondary)
            }
            if model.trustedPhones.isEmpty {
                Text("还没有配对的手机").foregroundStyle(.secondary)
            }
            ForEach(model.trustedPhones) { phone in
                LabeledContent {
                    Button("取消配对") { model.unpairPhone(id: phone.id) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(phone.name)
                        Text(model.phoneState.connectedPhone?.id == phone.id ? "已配对 · 已连接" : "已配对")
                            .font(.caption)
                            .foregroundStyle(model.phoneState.connectedPhone?.id == phone.id ? Palette.inRange : Color.secondary)
                    }
                }
            }
        } header: {
            Text("手机")
        } footer: {
            footnote("在手机「手环血糖」的 设置 → Mac 桌面端 里点「配对」，两边确认配对码一致即可；之后同一 Wi-Fi 下自动连接，数据全程加密。")
        }
    }

    private var logSection: some View {
        Section {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(model.log.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .textSelection(.enabled)
            }
            .frame(height: 220)
            HStack {
                Text("\(model.log.count) 条，新的在上").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("复制") { copyLog() }
            }
        } header: {
            Text("诊断日志")
        }
    }

    private func footnote(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// 按时间先后复制，方便贴给别人看。
    private func copyLog() {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(model.log.reversed().joined(separator: "\n"), forType: .string)
    }
}
