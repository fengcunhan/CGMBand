import Foundation
import Network

/// 在 NWConnection 上收发以 `\n` 结尾的单行帧（≤ 64 KB）。所有回调都在 `queue` 上执行。
final class LineChannel {
    var onReady: (() -> Void)?
    var onLine: ((Data) -> Void)?
    var onClose: ((String) -> Void)?

    let connection: NWConnection
    private let queue: DispatchQueue
    private let maxFrame: Int
    private var buffer = Data()
    private var closed = false
    private var closing = false

    init(connection: NWConnection, queue: DispatchQueue, maxFrame: Int = DesktopProtocol.maxFrameBytes) {
        self.connection = connection
        self.queue = queue
        self.maxFrame = maxFrame
    }

    var peerDescription: String { "\(connection.endpoint)" }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in self?.stateChanged(state) }
        connection.start(queue: queue)
        receiveNext()
    }

    func send(_ line: Data) {
        guard !closed, !closing else { return }
        guard line.count < maxFrame else { return finish("待发送的帧过大") }
        connection.send(content: line + Data([0x0A]), completion: .contentProcessed { [weak self] error in
            guard let error else { return }
            self?.finish("发送失败：\(error)")
        })
    }

    /// 发送最后一帧后关闭（例如 error / rejected）。对方不读也最多等 2 秒。
    func sendThenClose(_ line: Data, reason: String) {
        guard !closed, !closing else { return }
        closing = true
        connection.send(content: line + Data([0x0A]), contentContext: .finalMessage, isComplete: true,
                        completion: .contentProcessed { [weak self] _ in self?.finish(reason) })
        queue.asyncAfter(deadline: .now() + 2) { [weak self] in self?.finish(reason) }
    }

    func close(_ reason: String) {
        finish(reason)
    }

    private func stateChanged(_ state: NWConnection.State) {
        switch state {
        case .ready: onReady?()
        case let .failed(error): finish("连接失败：\(error)")
        case let .waiting(error): finish("无法连接：\(error)")
        case .cancelled: finish("连接已关闭")
        default: break
        }
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty { self.consume(data) }
            guard !self.closed else { return }
            if let error { return self.finish("接收失败：\(error)") }
            if isComplete { return self.finish("对方已断开") }
            self.receiveNext()
        }
    }

    private func consume(_ data: Data) {
        buffer.append(data)
        while !closed, !closing, let newline = buffer.firstIndex(of: 0x0A) {
            var line = buffer.subdata(in: buffer.startIndex..<newline)
            buffer = buffer.subdata(in: buffer.index(after: newline)..<buffer.endIndex)
            if line.last == 0x0D { line.removeLast() }
            guard line.count <= maxFrame else { return finish("帧超过 64 KB") }
            if !line.isEmpty { onLine?(line) }
        }
        if buffer.count > maxFrame { finish("帧超过 64 KB") }
    }

    private func finish(_ reason: String) {
        guard !closed else { return }
        closed = true
        buffer = Data()
        connection.cancel()
        let callback = onClose
        onClose = nil
        onLine = nil
        onReady = nil
        callback?(reason)
    }
}
