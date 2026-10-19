# CGMBand — 三诺 iCan 血糖实时显示到小米手环 9 Pro

```
三诺传感器 ──BLE──> 官方 iCan App (com.sino.care，不改动，报警/云同步照常)
                          │ 常驻通知（约 3 分钟刷新一次）
                          ▼
              android/  手机端 App (com.fch.cgmband)
                          │ NotificationListenerService 读通知 → 算趋势 → JSON
                          │ 小米穿戴 SDK (xms-wearable-lib 1.4)，经小米运动健康转发
                          ▼
              band/     手环 Vela JS 应用 (同包名、同签名)
```

> 手环上的显示只作方便查看。低血糖报警继续依赖官方 iCan App。

## 目录

| 路径 | 内容 |
|---|---|
| `android/` | 手机端 Kotlin 工程。`core/` 是纯逻辑（有 JVM 单测），其余是 Android 组件 |
| `band/` | 手环端 Vela JS 工程（aiot-toolkit 2.x） |
| `keystore/` | 手机端与手环端**共用**的签名（gitignore，丢了就得两端一起重签重装） |

## 关键约束

- **包名 + 签名必须两端一致**，否则 interconnect 直接拒绝。包名 `com.fch.cgmband`；
  签名在 `keystore/cgmband.jks`，手环端用的 pem 由它导出到 `band/sign/{debug,release}/`。
- 手环 9 / 9 Pro **不支持联网**，数据只能由手机通过 interconnect 推送。
- 读数来自 iCan 通知的自定义布局（RemoteViews），按 view 资源名取值：
  v2.5.x 为 `tv_cgm_value` / `tv_cgm_unit` / `tv_cgm_time`，v2.4.x 为 `new_blood_tv` / `new_blood_unit_tv`。
  iCan 升级后若读不到数，先用 `uiautomator dump` 看通知里的新 id，再改 `NotificationParser`。
- 趋势箭头由手机端按最近两次读数自行计算（1/2/3 mg/dL/min 阈值），不识别 iCan 的箭头图标。

## 消息协议（JSON 字符串）

手机 → 手环：

```json
{"type":"bg","value":"6.6","unit":"mmol/L","mmol":6.6,"time":"16:33",
 "ts":1792398000000,"trend":"FLAT","delta":0.1,"connected":true}
```

- `value`：iCan 显示的原文（可能是 `LO`/`HI`）；`mmol`：换算后的数值，非数字时为 `null`
- `ts`：**测量时间**。由 `time` 结合 iCan 通知的发布时间（`sbn.postTime`）推算，可跨零点前后各一天；
  推不出来（缺时间控件、与发布时间相差超过 6 小时）时为 `null`。**绝不用"现在"兜底**——否则旧值会显示成新值。
  手环把 `ts == null` 显示为"时间未知"并置灰，超过 10 分钟显示"数据已过期"
- `trend`：`DOUBLE_UP | SINGLE_UP | FORTY_FIVE_UP | FLAT | FORTY_FIVE_DOWN | SINGLE_DOWN | DOUBLE_DOWN | NONE`
- `delta`：与上一次读数之差（mmol/L，一位小数），无法计算时为 `null`
- `connected`：iCan 通知里出现"未连接/断开"、或 iCan 常驻通知消失时为 `false`（重发上一条读数，`ts` 不变），手环置灰
- 更旧的读数（`ts` 倒退）不会覆盖新的；最新值是 LO/HI 时 `trend=NONE`、`delta=null`

手环 → 手机：`{"type":"sync"}`，手机回发最近一条读数（手环应用打开 / 重连时发送）。

## 构建

```bash
# 手机端（需要 JDK 17 + Android SDK 36）
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 手环端（aiot 每次构建会清空 dist/）
cd band && npm install && npm test && npx aiot build && npx aiot release   # 产物在 band/dist/
```

## 手机端首次设置（HyperOS）

HyperOS 的后台冻结器（`dumpsys greezer`）会冻结后台应用，冻结期间通知回调全部积压（实测一次延迟 22 分钟）。
因此 App 带一个前台服务 `KeepAliveService`（通知栏常驻一条低优先级通知，显示最新血糖），
并且需要以下设置，缺一不可：


1. 给本应用开**自启动**，否则系统会拒绝绑定通知监听服务（日志里是 `AutoStartManagerService: Reject service`）：
   设置里手动开，或 `adb shell cmd appops set com.fch.cgmband 10008 allow`。
   **每次 `adb install -r` 重装后 HyperOS 会把它重置为 ignore，需要重新打开。**
2. 开**通知使用权**：App 里点「开启通知使用权」，或
   `adb shell cmd notification allow_listener com.fch.cgmband/com.fch.cgmband.CgmNotificationListener`
3. 省电策略设为「无限制」（等价于电池优化白名单，`dumpsys deviceidle whitelist` 里应有 `com.fch.cgmband`）。
   有了它，前台服务才能在后台被通知监听服务拉起
4. 允许通知（前台服务的常驻通知要用）
5. 手环应用装好后，App 里点「申请手环通信授权」→「在手环上打开血糖应用」

验证：熄屏 7 分钟，`adb logcat -s CgmBand` 里"已推送到手环"应每 3 分钟出现一次，
`adb shell dumpsys greezer | grep 10294`（换成本应用 uid）不应有新的 `FZ` 记录。

## 高低血糖提醒

手机端在每条新读数上跑 `core/AlertPolicy`，越界时用小米穿戴 SDK 的 `NotifyApi.sendNotify` 给手环推一条系统通知（会震动，与手环应用是否打开无关）。

- 默认阈值（mmol/L）：过低 < 3.1、低 < 3.9、高 > 13.9，可在 App 里改；`LO` 按过低、`HI` 按高处理
- 进入区间或恶化（低 → 过低）立即提醒；持续未恢复时，过低 10 分钟、低 20 分钟、高 60 分钟重复一次；从过低好转到低不提醒
- 无测量时间或超过 15 分钟的读数不触发提醒
- 需要手环授予 `NOTIFY` 权限（App 里「申请手环通信授权」会一并申请），用「发送测试提醒到手环」验证
- 仅作辅助，报警以 iCan 为准

**为什么没有做血糖表盘**：手环 9 Pro 支持社区逆向的 Lua 表盘（时间/日期/步数/天气可用 `dataman` 读），
但 Vela 快应用退到后台即被停止（仅音频/上传下载/定位可后台运行），表盘也没有接收手机数据的接口，
因此表盘拿不到实时血糖。快捷查看方式：小米运动健康 → 设备 → 系统设置 → 应用顺序管理，把「血糖」拖到第一位。

## 安装手环应用（rpk）

官方侧载需要小米商务提供的 Debug 版小米运动健康，个人拿不到，所以用社区工具 [AstroBox](https://astrobox.online)（开源，支持手环 9 Pro）：

1. **拿 AuthKey**：小米运动健康 →「设备」页同步 →「我的 → 关于」快速点图标 10 次以上 → 确定（打开调试日志）→ 再同步一次。
   AuthKey 在 `/sdcard/Android/data/com.mi.health/files/log/XiaomiFit.main.log`（及 `.bak.1`）里：
   是与 `"productName": "小米手环9 Pro"` 同一个 JSON 对象中的 `"token"`（32 位 hex），**不是**名为 `authKey` 的字段（那个是 null）。
   取出后存在 `keystore/band_authkey.txt`（gitignore）。
   **不要**用 AstroBox 的"登录小米账号导入设备"——那要把小米账号交给第三方。
2. 手环上：「设置 → 系统操作 → 连接新手机」，进入配对模式
3. AstroBox（macOS 版来自 GitHub `AstralSightStudios/AstroBox-NG`，ad-hoc 签名，需右键打开）：
   搜索设备 → 填 AuthKey 连接 → 队列 → 快应用 → 选择 `band/dist/` 下的 release rpk 安装
4. 装完后退出配对模式，手环会用同一个 AuthKey 回连小米运动健康（不需要解绑）

## 排错

- 手机端日志：`adb logcat -s CgmBand CgmExtractor`，App 首页底部也有同样的事件日志
- `AppNotInstalledException`：手环上还没装应用，或包名不一致
- `SignatureVerifyFailedException`：rpk 和 APK 签名不同，检查 `band/sign/` 是否由 `keystore/cgmband.jks` 导出
- 通知监听不工作：看 `adb shell dumpsys notification` 的 "Live notification listeners" 里有没有 `com.fch.cgmband`
