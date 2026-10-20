# 手机 → Mac 桌面端协议（v1）

手机 App「手环血糖」在同一 Wi-Fi 下自动发现 Mac 上的接收端，配对后把实时血糖和最近 4 小时历史加密推送过去，Mac 在菜单栏显示。
本文是两端共同遵守的规格；任何实现都必须通过文末的测试向量。

## 1. 发现

- Mac 端用 Bonjour 发布 TCP 服务，类型 `_cgmband._tcp`，服务名为 Mac 的显示名。
- TXT 记录：`id=<Mac 设备 ID>`、`proto=1`。
- 手机端只在连着 Wi-Fi 时搜索；后台只自动连接**已配对**的 Mac（按 TXT 里的 `id` 匹配），未配对的 Mac 只在设置页列出，由用户点「配对」发起。

## 2. 帧格式

- TCP 上传输 UTF-8 JSON，每帧一行，以 `\n` 结尾；单帧不超过 64 KB。
- 握手阶段的帧是明文；握手完成后所有帧都是加密帧：

```json
{"type":"enc","seq":0,"ct":"<base64(密文 || 16 字节 tag)>"}
```

- AES-256-GCM；12 字节 nonce = 4 个 `0x00` + `seq` 的 8 字节大端整数；附加认证数据（AAD）为 ASCII `cgmband/v1`。
- 每个方向各用一把密钥（见 §4），`seq` 在每次连接、每个方向上从 0 开始，每帧加 1。收到的 `seq` 必须正好等于期望值，否则立即断开（防重放、防乱序）。
- 解密后的明文是一行 JSON（内层消息，见 §6）。

## 3. 身份

- 每台设备有一个长期 P-256 密钥对，首次运行时生成并保存在本机私有存储中，**不备份、不导出**。
- 公钥用 X9.63 非压缩格式（65 字节：`0x04 || X || Y`），JSON 里用 base64。
- 设备 ID：16 字节随机数的十六进制（32 个字符），首次运行时生成。

## 4. 握手

```
手机 → Mac  {"type":"hello","proto":1,"id":"…","name":"Xiaomi 13 Ultra","pub":"…","nonce":"…","commit":"…"}
Mac  → 手机 {"type":"hello","proto":1,"id":"…","name":"fch 的 MacBook","pub":"…","nonce":"…","pairNonce":"…"}
手机 → Mac  {"type":"reveal","r":"…"}
```

- `nonce`、`pairNonce`、`r`：各 16 字节随机数（base64），每次连接重新生成。
- `commit = SHA-256("cgmband/v1/commit" || 手机公钥 || r)`（base64）。Mac 收到 `reveal` 后必须校验，不匹配就断开。
- 版本：Mac 不支持手机的 `proto` 时回复 `{"type":"error","code":"proto"}` 并断开。
- 密钥（双方各自计算）：
  - `shared` = ECDH(本机长期私钥, 对方长期公钥)，即 32 字节 X 坐标
  - `p2m` = HKDF-SHA256(ikm=`shared`, salt=`手机 nonce || Mac nonce`, info=`cgmband/v1/p2m`, 32 字节)：手机 → Mac 方向
  - `m2p` = 同上，info=`cgmband/v1/m2p`：Mac → 手机方向

`reveal` 之后的所有帧都是加密帧。

## 5. 信任与配对

握手后双方立刻发送（加密）：

```json
{"type":"trust","known":true}
```

`known` 表示本机是否已经信任对方：本机保存过对方 `id`，并且保存的公钥与这次 `hello` 里的一致。

- **双方都为 `true`**：直接进入数据阶段。
- **任意一方为 `false`**：进入配对。双方计算并显示同一个 6 位配对码：
  - `sas` = HKDF-SHA256(ikm=`shared`, salt=SHA-256(`手机公钥 || Mac 公钥 || r || pairNonce`), info=`cgmband/v1/sas`, 4 字节)
  - 4 字节按大端读成无符号整数，对 1 000 000 取模，补零到 6 位
  - 用户在**两边**都确认配对码一致后，各自保存对方的 `id` 和公钥，并发送 `{"type":"paired"}`；拒绝则发送 `{"type":"rejected"}` 并断开
  - 双方都收到对方的 `paired` 后进入数据阶段
- 手机在**后台**自动连接时，如果遇到需要配对的情况，不弹窗，直接断开，并在设置页标记「需要重新配对」。

`commit`/`reveal` 的作用：手机先承诺自己的随机数，等拿到 Mac 的 `pairNonce` 之后才公开。中间人因此无法离线挑选密钥，让两边的配对码碰巧一致；攻击一次成功的概率只有一百万分之一。

## 6. 数据阶段（内层消息）

| 方向 | 消息 | 说明 |
|---|---|---|
| 手机 → Mac | `bg` | 与手环协议相同（README「消息协议」），每条新读数发送一次 |
| 手机 → Mac | `history` | 与手环协议相同：最近 4 小时、`[距 ref 分钟数, mmol×10]`，附目标范围 `range` |
| Mac → 手机 | `{"type":"sync"}` | 请手机重发最新的 `bg` 和 `history` |
| 双向 | `{"type":"ping"}` / `{"type":"pong"}` | 空闲 30 秒发一次 `ping`，收到即回 `pong`；75 秒没收到任何帧就断开重连 |

- 进入数据阶段后，手机立即发送一次 `bg` 和 `history`。
- 未知的 `type` 一律忽略。

## 7. 测试向量

由 `tools/desktop_vectors.swift`（CryptoKit）生成。固定输入：

| 输入 | 值 |
|---|---|
| 手机长期私钥 | 32 个字节 `0x11` |
| Mac 长期私钥 | 32 个字节 `0x22` |
| 手机 `nonce` | 16 个字节 `0xA0` |
| Mac `nonce` | 16 个字节 `0xB0` |
| 手机 `r` | 16 个字节 `0xC0` |
| Mac `pairNonce` | 16 个字节 `0xD0` |

期望输出：

```
phonePub=BAIX5hfwtkQ5KCePlpmeaaI6TywVK99tbN9m5bgCgtTtGUp968uXcS0t2jyoWqh2Wlb0X8dYWZZS8ol8ZTBuV5Q=
macPub=BNZak5d8qj0bCBhS/1ennkZfFmBXcwS66tUF3TpIWJzzUBheiVNy32Ih6joTdVfkc/3bZ1XwW9UHw8Uz/OnJEoU=
commit=WUDIKaJsGLh80Ociel3tFWLw+xCpTChZn1+dCw7AX3Y=
shared=ccfc261f58193c98ca4ad4a53bbac6f0ee29bc4d48438090446908622ca79af6
p2m=94f16e12d76d5e02f2c75a16de0a0dc2c71225dbfde5b24e462e3fa2cb3b1303
m2p=00d90b12dd5eb8bff60c5703754bb0236bc3b121d7715a71fa17d4ff44e97aa2
sas=506367
ct_p2m_seq0=uOa/vkl8jorvL+vvUXQkUaBAky3CsdHK9/J+IDkjvA==      # 明文 {"type":"ping"}，p2m 密钥，seq 0
ct_m2p_seq5=Z1MmDyXGsW26uezhzXXWNDRJ4a7hylV1sznn5JaHgQ==      # 明文 {"type":"sync"}，m2p 密钥，seq 5
```
