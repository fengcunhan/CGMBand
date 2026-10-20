import CryptoKit
import Foundation

/// 本机长期身份：P-256 密钥对 + 16 字节随机设备 ID。
/// 保存在 ~/Library/Application Support/CGMMonitor/identity.json（目录 0700、文件 0600，排除备份）。
struct DesktopIdentity {
    let deviceId: String
    let privateKey: P256.KeyAgreement.PrivateKey

    /// X9.63 非压缩公钥（65 字节）。
    var publicKey: Data { privateKey.publicKey.x963Representation }

    enum Failure: Error, CustomStringConvertible {
        case createDirectory(String)
        case write(String)

        var description: String {
            switch self {
            case let .createDirectory(m): return "无法创建身份目录：\(m)"
            case let .write(m): return "无法写入身份文件：\(m)"
            }
        }
    }

    private struct Stored: Codable {
        let version: Int
        let deviceId: String
        let privateKey: String   // base64(32 字节原始私钥)
    }

    static let fileName = "identity.json"

    static var defaultDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent("CGMMonitor", isDirectory: true)
    }

    /// 读取已有身份；不存在则生成。文件损坏时改名备份后重新生成（已配对的手机需要重新配对）。
    static func loadOrCreate(in directory: URL = defaultDirectory) throws -> DesktopIdentity {
        try ensurePrivateDirectory(directory)
        let file = directory.appendingPathComponent(fileName)
        if FileManager.default.fileExists(atPath: file.path) {
            if let identity = read(file) {
                chmod(file.path, 0o600)
                return identity
            }
            let backup = directory.appendingPathComponent("identity.corrupt-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.moveItem(at: file, to: backup)
        }
        let identity = DesktopIdentity(deviceId: DesktopCrypto.randomBytes(16).hexString,
                                       privateKey: P256.KeyAgreement.PrivateKey())
        try write(identity, to: file)
        return identity
    }

    private static func ensurePrivateDirectory(_ dir: URL) throws {
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
        } catch {
            throw Failure.createDirectory(error.localizedDescription)
        }
        chmod(dir.path, 0o700)
    }

    private static func read(_ file: URL) -> DesktopIdentity? {
        guard let data = try? Data(contentsOf: file),
              let stored = try? JSONDecoder().decode(Stored.self, from: data),
              PeerHello.isDeviceId(stored.deviceId),
              let raw = Data(base64Encoded: stored.privateKey),
              let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: raw)
        else { return nil }
        return DesktopIdentity(deviceId: stored.deviceId, privateKey: key)
    }

    /// 先以 0600 创建临时文件再原子改名，私钥任何时刻都不会以宽松权限落盘。
    private static func write(_ identity: DesktopIdentity, to file: URL) throws {
        let stored = Stored(version: 1, deviceId: identity.deviceId,
                            privateKey: identity.privateKey.rawRepresentation.base64EncodedString())
        guard let data = try? JSONEncoder().encode(stored) else { throw Failure.write("编码失败") }
        let tmp = file.deletingLastPathComponent().appendingPathComponent(".identity-\(UUID().uuidString).tmp")
        let fd = open(tmp.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard fd >= 0 else { throw Failure.write(String(cString: strerror(errno))) }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        do {
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
        } catch {
            unlink(tmp.path)
            throw Failure.write(error.localizedDescription)
        }
        guard rename(tmp.path, file.path) == 0 else {
            let message = String(cString: strerror(errno))
            unlink(tmp.path)
            throw Failure.write(message)
        }
        var url = file
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }
}
