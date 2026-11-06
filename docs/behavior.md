# Airpipe Android 端行为设计

**版本 v1.2（草案，待实现校准）** · 帧与加密语义见根仓库 `docs/protocol.md`；本文定义移动端行为、性能与稳定性设计。实现变动须同步更新本文并记入文末。

## 技术约束（探针实测结论，全部已在 Redmi Note 13 Pro / HyperOS 真机验证）

1. **入站必死**：HyperOS 静默丢弃 App 监听端口的一切入站 SYN（连 loopback 都不通）→ 只能做 TCP 客户端主动出站。
2. **读剪贴板要焦点**：Android 10+ 仅焦点窗口 / 默认 IME 可读 → 蹦床 Activity `onWindowFocusChanged` 读。
3. **写剪贴板免焦点**：`setPrimaryClip` 后台可用 → PC→手机文本**自动落剪贴板，零点击**。
4. **后台 3 秒冻结**：SmartPower 将后台 App 打入 idle，socket 被掐 → 省电策略必须手动「无限制」。
5. **通知可被划掉**：Android 13+ FGS 通知可划掉而服务存活 → deleteIntent 自动复活；onStartCommand 每次重挂 startForeground。

## 组件清单

| 组件 | 职责 |
| :--- | :--- |
| `SyncService` | FGS 常驻。TLS 客户端连接循环、退避重拨、心跳看门狗、收发帧、出站队列、自动写剪贴板、常驻通知 |
| `SyncTileService` | 快捷设置磁贴（Clash 式）：轮询总开关。开 = 后台轮询 + 常驻通知；关 = 停 SyncService 全停 |
| `Prefs` | 轻量持久化：`sync_enabled` 总开关（默认开），磁贴 / App 按钮 / 各自启入口共用 |
| `TrampolineActivity` | 透明蹦床：焦点瞬间读剪贴板（文本 / 文件 URI）→ 入队 → 自毁。点击常驻通知触发（通知即同步，不开 App） |
| `ShareReceiverActivity` | `ACTION_SEND` / `SEND_MULTIPLE` 分享接收：流式读 URI 入队，零 UI |
| `PairActivity` | **唯一可见 UI**（同时是桌面图标入口）：连接状态、轮询总开关、6 位码输入、手动 IP（高级）、已配对 PC 列表 / 取消配对、权限引导 |
| `BootReceiver` | 开机自启 SyncService（RECEIVE_BOOT_COMPLETED；HyperOS 还需用户开「自启动」） |
| `DismissReceiver` | 常驻通知被划掉 → 重拉服务重挂通知 |

零三方依赖（纯框架 API），minSdk 29 / targetSdk 34，`applicationId = com.morethan.airpipe`。

## 轮询总开关（磁贴）

- 入口两处，同走 `SyncService.setSyncEnabled`，状态持久化（`Prefs.sync_enabled`，默认开保持既有行为）：
  ① 下拉快捷设置磁贴「Airpipe 同步」（长按下拉面板 → 编辑磁贴 → 拖入，一次性操作）；
  ② PairActivity「轮询同步」按钮。
- **开** = 现状不变：后台自动连接 PC + 常驻通知。
- **关** = 停 SyncService，轮询与常驻通知一并消失（省电）。所有复活路径统一过 `Prefs.syncEnabled` 闸门，无死角：
  `kick()` 不拉服务（分享滞队随进程死即丢，分享侧 Toast 显式提示）、`onCreate/onStartCommand` 满足 FGS 契约后立即自灭、`refreshNotification` 静默跳过、开机 / 覆盖安装 / 通知划掉兜底均不自启。
- 例外：**配对 = 明确要同步**，提交配对码时若总开关关闭则顺带打开，否则配对永远无响应。

## 连接管理（SyncService 核心）

- 状态机：`未配对 → 发现中 → 连接中 → 在线 → (断线) → 退避等待 → 发现中 …`
- 发现：UDP 广播（协议 §2），健康连接时不广播；失败清缓存地址重发现（容忍 DHCP）。
- TLS：`javax.net.ssl.SSLContext`，KeyManager 挂本机身份证书（`files/identity.p12`），TrustManager 做指纹固定——对端证书指纹在已配对库中才放行；配对流程中才接受未知指纹。
- 退避：2s 起指数翻倍封顶 60s；用户动作（分享 / 点通知 / 配对提交 / 打开 App）nudge 立即全速重拨。
- 看门狗：`soTimeout = 45s`，读超时即判死链断开重拨；同时应答 PC 每 15s 心跳。双向探活缺一不可（半开连接无 FIN/RST，探针实测）。
- 出站队列：内存态 `ConcurrentLinkedQueue`，元素为**引用**（URI / 文本），任务只在整帧进 socket 且收到 `F(status=0)` 后出队；发送失败留队，重连自动补发；**不可读 URI（授权失效/文件被删）终态跳过不堵队**；进程被杀即丢（与 PC 队列同哲学）。

## 剪贴板行为

| 方向 | 触发 | 行为 |
| :--- | :--- | :--- |
| PC → 手机 | PC 复制（自动） | `T` 帧到达 → SyncService 直接 `setPrimaryClip`（写免焦点，**零点击**）。写入异常（个别 ROM 限制）→ 降级：通知动作「复制」走蹦床写入 |
| PC → 手机 | PC 复制图片（自动） | `I` 帧 → 同上直接写入剪贴板（ClipData 带 MIME） |
| 手机 → PC | 点常驻通知（或分享面板） | 蹦床获焦点读剪贴板：纯文本 → `T`；`content://` 文件 URI → 按文件流发送（HyperOS 文件管理器「copy to clipboard」实测可用） |
| 手机 → PC | 文本到达 | daemon 直接写 PC 剪贴板 |

回声抑制由 PC 侧负责（协议 §9），手机侧不自动读剪贴板，无环。

## 文件传输

### 发送（手机 → PC）

- 主路径：任意文件管理器 / 相册 → **分享 → Airpipe**。读授权随 Intent 授予。
- **流式**：`contentResolver.openInputStream` + 128 KiB 循环，绝不整读进内存（探针 32MB 上限的根除）。URI 查不到 size（部分 provider）→ `B.flags.bit0 = size_unknown`。
- 文件名取 `OpenableColumns.DISPLAY_NAME`，退化用 `lastPathSegment`。
- 多文件分享按序逐个传输；每文件完整收 `F(0)` 才算成功。

### 接收（PC → 手机）

- 落盘 `/storage/emulated/0/Airpipe/`（`MANAGE_EXTERNAL_STORAGE` appops 授权），relPath 递归建目录（协议 §7 消毒）。
- **流式落盘**：`FileOutputStream` + 128 KiB 桶直写 `.part` 临时文件，收满 `E` 校验 sha256 后改名——半截文件永不暴露正式名。
- 每 4 MiB 回 `R` 进度帧；完成回 `F`（含 sha256）。
- 成功后 `MediaScannerConnection.scanFile` 通知相册/文件管理器；常驻通知更新「最近事件」。
- 批量聚合（v1.4.0，协议 §6）：PC 的一次发送动作以 `G` 帧划界；批内传输通知不逐文件撤/建（消除闪烁），进度按批字节汇总（label = 文件夹名/项目数），批收尾发**一条**事件：「收到文件夹：docs（17 个文件 · 45MB）」/「收到 3 个项目（…）」/ 单文件仍为「收到文件：name」。未见 G 的 B 帧流（旧 PC）落隐式批降级。
- 目录骨架（`B.flags.bit1`）原样重建。

## 通知设计（两条通知，KDE Connect 同款结构）

**常驻通知**（固定 ID，FGS；通道「Airpipe 同步守护」IMPORTANCE_LOW）：

```
标题：Airpipe — 已连接 <PC名>   （连接中… / 未配对 / 退避等待中… / 同步已关闭）
正文：始终为「最近事件」（文件已送达 / 收到文件 / 剪贴板同步等），永不挂进度条
点击通知 = 立即同步               ← contentIntent → 蹦床读剪贴板入队发 PC，不开 App
动作：[配对] 或 [打开应用]         ← 未配对 / 已配对时显示其一
       [收件目录]                  ← SAF 文档 URI + MIME_TYPE_DIR 直达系统文件管理器
       [复制]                      ← 仅 PC 文本写入失败降级时出现
```

**传输通知**（独立 ID + 独立通道「Airpipe 传输进度」IMPORTANCE_LOW）：

```
标题：Airpipe — 传输中
正文：↓ 批标签 + 百分比（批级字节汇总：↓ bigdir 42%；单批单文件即文件名）
      总量未知（隐式批）时转圈
整批收尾立即 cancel——完成信息由常驻通知一条聚合事件承载
（v1.4.0 起批内不再逐文件撤/建，结构性消除多文件闪烁）
```

- 进度条与常态文字分属两条通知，结构上不可能交替闪烁；无定时器/去重等额外状态。
- `setShowWhen(false)` 隐藏无意义时间戳，减少高频更新时整行重排。
- deleteIntent → DismissReceiver 复活（Android 13+ 可划掉的 FGS 通知）。
- 总开关关闭时两条通知随服务一并消失（见「轮询总开关」节）。

## 性能与稳定性清单

| 项 | 手段 |
| :--- | :--- |
| 大文件内存 | 全链路流式（分享 URI 流式读 / 接收 .part 流式写），峰值内存 ≈ 128 KiB 桶 |
| 可靠性 | 帧层 sha256 终验（`F`），不符由 PC 整文件重发一次 |
| 传输进度 | 独立传输通知进度条：↑发送 / ↓接收按字节，300ms 节流刷新，结束即撤 |
| 半开死链 | 双向心跳 + 45s 读超时看门狗 |
| 断线补发 | 出站引用队列 + 发送失败留队 + 重连清队 |
| 冻结恢复 | START_STICKY + BootReceiver + deleteIntent 复活 + 用户 nudge 全速重拨 |
| 交互延迟 | 分享/点通知在服务未起时冷启动挂服务（探针已验证路径） |
| 省电 | 总开关关闭 = 服务全停（不轮询 / 无通知 / 不自启），磁贴一键切换 |

## 权限与一次性授权仪式

```xml
POST_NOTIFICATIONS            <!-- 13+ 运行时；HyperOS 需手动设置开启 -->
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC  <!-- Android 14 强制类型声明 -->
INTERNET
MANAGE_EXTERNAL_STORAGE      <!-- 写 /storage/emulated/0/Airpipe -->
RECEIVE_BOOT_COMPLETED
```

装机后：①PairActivity 引导页点「开启存储权限」跳系统「所有文件访问」页，用户手开（普通用户无需 adb）；②手动开通知权限；③省电策略「无限制」+ 自启动（引导页分步列出）；④（可选）下拉编辑磁贴拖入「Airpipe 同步」。

## 已知限制

- 校园网级大平面网链路层限杀互访流量 → 不可用也不解决（换热点即好）；广播不可达时用配对页手动 IP。
- 剪贴板「copy to clipboard 文件 URI」依赖文件管理器支持（HyperOS 自带实测支持；第三方 FM 不保证，分享面板是通用兜底）。
- 单 PC 场景；UI 不暴露多设备管理。

## 变更日志

- 2026-11-04 v1 初稿（审议中）
- 2026-11-05 v1.2.0 新增轮询总开关：快捷设置磁贴 `SyncTileService` + App 内按钮；关闭时全停（不轮询 / 无常驻通知 / 开机与覆盖安装不自启 / 分享不复活），配对自动打开。
- 2026-11-06 v1.3.0 协议 v2：接收 D 帧解压（zstd）+ 发送端逐块压缩（白名单跳过已压格式）；writerLoop 文件级流水线窗口（SEND_WINDOW=4，等 F 不再逐文件阻塞）；引入唯一三方依赖 `zstd-jni`（AAR 内置各 ABI .so）。
- 2026-11-06 v1.4.0 批量事件聚合（协议 v2.1 `G` 帧）：一次发送 = 一个事件。接收侧 `Batch` 按批聚合进度/结果；常驻通知批完成文案（文件夹/多项目/失败计数）；zstd 解压与既有行为不变。真机验证：55MB/150MB 文件夹、多文件批、同文件连发均一条事件无闪烁。