import Foundation

struct TrustedPhone: Equatable {
    let id: String
    let publicKey: Data
    let name: String
}

/// 已信任的手机：UserDefaults 里 id → {"pub": base64 公钥, "name": 名称}。线程安全。
final class TrustStore {
    private let defaults: UserDefaults
    private let key: String
    private let lock = NSLock()

    init(defaults: UserDefaults = .standard, key: String = "trustedPhones") {
        self.defaults = defaults
        self.key = key
    }

    func all() -> [TrustedPhone] {
        locked { decode(load()) }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    func phone(id: String) -> TrustedPhone? {
        locked { decode(load()).first { $0.id == id } }
    }

    /// 已保存过对方 id，并且保存的公钥与这次 hello 里的一致。
    func isTrusted(id: String, publicKey: Data) -> Bool {
        phone(id: id)?.publicKey == publicKey
    }

    func save(_ phone: TrustedPhone) {
        locked {
            let updated = load().merging([phone.id: ["pub": phone.publicKey.base64EncodedString(),
                                                     "name": phone.name]]) { _, new in new }
            defaults.set(updated, forKey: key)
        }
    }

    /// 手机改名后更新显示名（公钥不变才更新）。
    func rename(id: String, publicKey: Data, to name: String) {
        guard let existing = phone(id: id), existing.publicKey == publicKey, existing.name != name else { return }
        save(TrustedPhone(id: id, publicKey: publicKey, name: name))
    }

    func remove(id: String) {
        locked {
            let updated = load().filter { $0.key != id }
            defaults.set(updated, forKey: key)
        }
    }

    private func load() -> [String: [String: String]] {
        defaults.dictionary(forKey: key) as? [String: [String: String]] ?? [:]
    }

    private func decode(_ raw: [String: [String: String]]) -> [TrustedPhone] {
        raw.compactMap { id, entry in
            guard let pub = entry["pub"].flatMap({ Data(base64Encoded: $0) }) else { return nil }
            return TrustedPhone(id: id, publicKey: pub, name: entry["name"] ?? "Android 手机")
        }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
