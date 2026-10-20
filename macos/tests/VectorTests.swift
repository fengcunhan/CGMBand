import CryptoKit
import Foundation

/// docs/desktop-protocol.md §7 的全部测试向量。
func runVectorTests(_ t: TestRun) {
    t.section("测试向量（desktop-protocol.md §7）")
    guard let phoneKey = try? P256.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x11, count: 32)),
          let macKey = try? P256.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 0x22, count: 32))
    else { return t.check(false, "固定私钥可以导入") }

    let phonePub = phoneKey.publicKey.x963Representation
    let macPub = macKey.publicKey.x963Representation
    let phoneNonce = Data(repeating: 0xA0, count: 16)
    let macNonce = Data(repeating: 0xB0, count: 16)
    let r = Data(repeating: 0xC0, count: 16)
    let pairNonce = Data(repeating: 0xD0, count: 16)

    t.equal(phonePub.b64, "BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q=", "phonePub")
    t.equal(macPub.b64, "BNZak5d8qj0bCBhS/1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc/3bZ1XwW9UHw8Uz/OnJEoU=", "macPub")
    t.equal(DesktopCrypto.commit(phonePublicKey: phonePub, reveal: r).b64,
            "WUDIKaJsGLh80Ociel3tFWLw+xCpTChZn1+dCw7AX3Y=", "commit")

    guard let macSide = try? DesktopCrypto.sharedSecret(privateKey: macKey, peer: DesktopCrypto.publicKey(x963: phonePub)),
          let phoneSide = try? DesktopCrypto.sharedSecret(privateKey: phoneKey, peer: DesktopCrypto.publicKey(x963: macPub))
    else { return t.check(false, "ECDH 成功") }
    let expectedShared = "ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6"
    t.equal(macSide.hexString, expectedShared, "shared（Mac 侧）")
    t.equal(phoneSide.hexString, expectedShared, "shared（手机侧）")

    let keys = DesktopCrypto.sessionKeys(shared: macSide, phoneNonce: phoneNonce, macNonce: macNonce)
    t.equal(keys.p2m.hexString, "94f16e12d76d5e02f2c75a16de0a0dc2c71225dbfde5b24e462e3fa2cb3b1303", "p2m")
    t.equal(keys.m2p.hexString, "00d90b12dd5eb8bff60c5703754bb0236bc3b121d7715a71fa17d4ff44e97aa2", "m2p")
    t.equal(DesktopCrypto.sas(shared: macSide, phonePublicKey: phonePub, macPublicKey: macPub,
                              reveal: r, pairNonce: pairNonce), "506367", "sas")

    let ping = Data(#"{"type":"ping"}"#.utf8)
    let sync = Data(#"{"type":"sync"}"#.utf8)
    let ctP2M = "uOa/vkl8jorvL+vvUXQkUaBAky3CsdHK9/J+IDkjvA=="
    let ctM2P = "Z1MmDyXGsW26uezhzXXWNDRJ4a7hylV1sznn5JaHgQ=="
    t.equal((try? DesktopCrypto.seal(ping, key: keys.p2m, seq: 0))?.b64 ?? "", ctP2M, "ct_p2m_seq0")
    t.equal((try? DesktopCrypto.seal(sync, key: keys.m2p, seq: 5))?.b64 ?? "", ctM2P, "ct_m2p_seq5")

    let sealedP2M = Data(base64Encoded: ctP2M) ?? Data()
    let sealedM2P = Data(base64Encoded: ctM2P) ?? Data()
    t.equal(try? DesktopCrypto.open(sealedP2M, key: keys.p2m, seq: 0), ping, "解密 ct_p2m_seq0 → ping")
    t.equal(try? DesktopCrypto.open(sealedM2P, key: keys.m2p, seq: 5), sync, "解密 ct_m2p_seq5 → sync")
    t.check((try? DesktopCrypto.open(sealedM2P, key: keys.m2p, seq: 4)) == nil, "seq 不对无法解密")
    t.check((try? DesktopCrypto.open(sealedP2M, key: keys.m2p, seq: 0)) == nil, "方向密钥不对无法解密")
    var tampered = sealedP2M
    tampered[0] ^= 0x01
    t.check((try? DesktopCrypto.open(tampered, key: keys.p2m, seq: 0)) == nil, "篡改密文无法解密")

    // Mac 端的 FrameCipher（接收用 p2m）能解开手机的第 0 帧，并拒绝错序
    let macCipher = FrameCipher(sendKey: keys.m2p, receiveKey: keys.p2m)
    t.check((try? macCipher.open(EncFrame(seq: 1, ct: ctP2M))) == nil, "FrameCipher 拒绝 seq 1（期望 0）")
    t.equal(try? macCipher.open(EncFrame(seq: 0, ct: ctP2M)), ping, "FrameCipher 解开 seq 0")
    t.check((try? macCipher.open(EncFrame(seq: 0, ct: ctP2M))) == nil, "FrameCipher 拒绝重放 seq 0")
    t.equal(macCipher.nextReceiveSeq, 1, "接收 seq 前进到 1")
}
