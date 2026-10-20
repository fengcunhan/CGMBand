import Foundation

/// 极简测试运行器：统计通过/失败，失败时进程以非零码退出。
final class TestRun {
    private(set) var passed = 0
    private(set) var failed = 0

    func section(_ name: String) {
        print("\n== \(name)")
    }

    func check(_ condition: Bool, _ message: String, line: UInt = #line) {
        if condition {
            passed += 1
            print("  ok    \(message)")
        } else {
            failed += 1
            print("  FAIL  \(message)  (line \(line))")
        }
    }

    func equal<T: Equatable>(_ actual: T, _ expected: T, _ message: String, line: UInt = #line) {
        let ok = actual == expected
        check(ok, ok ? message : "\(message): got \(actual), expected \(expected)", line: line)
    }

    func finish() -> Never {
        print("\n\(passed) passed, \(failed) failed")
        exit(failed == 0 ? 0 : 1)
    }
}

/// 线程安全的收件箱：其它线程放入，测试线程按条件或按序号等待。
final class Inbox<T> {
    private let condition = NSCondition()
    private var items: [T] = []

    func put(_ item: T) {
        condition.lock()
        items.append(item)
        condition.broadcast()
        condition.unlock()
    }

    var count: Int {
        condition.lock()
        defer { condition.unlock() }
        return items.count
    }

    func snapshot() -> [T] {
        condition.lock()
        defer { condition.unlock() }
        return items
    }

    /// 等第一个（序号 ≥ from 的）满足条件的元素。
    func wait(from start: Int = 0, timeout: TimeInterval = 3, where match: (T) -> Bool) -> T? {
        let deadline = Date().addingTimeInterval(timeout)
        condition.lock()
        defer { condition.unlock() }
        while true {
            if let hit = items.dropFirst(start).first(where: match) { return hit }
            if !condition.wait(until: deadline) { return items.dropFirst(start).first(where: match) }
        }
    }

    /// 等序号为 index 的元素。
    func item(at index: Int, timeout: TimeInterval = 3) -> T? {
        let deadline = Date().addingTimeInterval(timeout)
        condition.lock()
        defer { condition.unlock() }
        while items.count <= index {
            if !condition.wait(until: deadline) { break }
        }
        return items.count > index ? items[index] : nil
    }
}

final class Locked<T> {
    private let lock = NSLock()
    private var value: T

    init(_ value: T) { self.value = value }

    func get() -> T {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func set(_ newValue: T) {
        lock.lock()
        value = newValue
        lock.unlock()
    }
}

extension Data {
    var b64: String { base64EncodedString() }
}

/// 测试共用一个固定的 UserDefaults 域（用前用后都清空），不会在 ~/Library/Preferences 留下一堆文件。
let testDefaultsSuite = "local.cgm.monitor.tests"

func freshTestDefaults() -> UserDefaults? {
    let defaults = UserDefaults(suiteName: testDefaultsSuite)
    defaults?.removePersistentDomain(forName: testDefaultsSuite)
    return defaults
}
