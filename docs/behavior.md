# Airpipe Android 端行为设计

**版本 v1（草案，待实现校准）** · 帧与加密语义见根仓库 `docs/protocol.md`；本文定义移动端行为、性能与稳定性设计。实现变动须同步更新本文并记入文末。

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
| `TrampolineActivity` | 透明蹦床：焦点瞬间读剪贴板（文本 / 文件 URI）→ 入队 → 自毁。通知栏「立即同步」触发 |
| `ShareReceiverActivity` | `ACTION_SEND` / `SEND_MULTIPLE` 分享接收：流式读 URI 入队，零 UI |
| `PairActivity` | **唯一可见 UI**（同时是桌面图标入口）：连接状态、6 位码输入、手动 IP（高级）、已配对 PC 列表 / 取消配对、权限引导 |
| `BootReceiver` | 开机自启 SyncService（RECEIVE_BOOT_COMPLETED；HyperOS 还需用户开「自启动」） |
| `DismissReceiver` | 常驻通知被划掉 → 重拉服务重挂通知 |

零三方依赖（纯框架 API），minSdk 29 / targetSdk 34，`applicationId = com.morethan.airpipe`。

## 连接管理（SyncService 核心）

- 状态机：`未配对 → 发现中 → 连接中 → 在线 → (断线) → 退避等待 → 发现中 …`
- 发现：UDP 广播（协议 §2），健康连接时不广播；失败清缓存地址重发现（容忍 DHCP）。
- TLS：`javax.net.ssl.SSLContext`，KeyManager 挂本机身份证书（`files/identity.p12`），TrustManager 做指纹固定——对端证书指纹在已配对库中才放行；配对流程中才接受未知指纹。
- 退避：2s 起指数翻倍封顶 60s；用户动作（分享 / 立即同步 / 配对提交 / 打开 App）nudge 立即全速重拨。
- 看门狗：`soTimeout = 45s`，读超时即判死链断开重拨；同时应答 PC 每 15s 心跳。双向探活缺一不可（半开连接无 FIN/RST，探针实测）。
- 出站队列：内存态 `ConcurrentLinkedQueue`，元素为**引用**（URI / 文本），任务只在整帧进 socket 且收到 `F(status=0)` 后出队；发送失败留队，重连自动补发。进程被杀即丢（与 PC 队列同哲学）。

## 剪贴板行为

| 方向 | 触发 | 行为 |
| :--- | :--- | :--- |
| PC → 手机 | PC 复制（自动） | `T` 帧到达 → SyncService 直接 `setPrimaryClip`（写免焦点，**零点击**）。写入异常（个别 ROM 限制）→ 降级：通知动作「复制」走蹦床写入 |
| PC → 手机 | PC 复制图片（自动） | `I` 帧 → 同上直接写入剪贴板（ClipData 带 MIME） |
| 手机 → PC | 通知栏「立即同步」 | 蹦床获焦点读剪贴板：纯文本 → `T`；`content://` 文件 URI → 按文件流发送（HyperOS 文件管理器「copy to clipboard」实测可用） |
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
- 目录骨架（`B.flags.bit1`）原样重建。

## 通知设计（单条常驻通知）

```
标题：Airpipe — 已连接 <PC名>   （连接中… / 未配对 / 退避等待中…）
正文：最近事件（「收到 2 个文件」「已推送剪贴板」…，无事件则收件目录路径）
动作：[立即同步]                    ← 蹦床读剪贴板发 PC
       [复制]                      ← 仅 PC 文本写入失败降级时出现
       [配对]                      ← 仅未配对时出现，打开 PairActivity
```

- `IMPORTANCE_LOW`（无声）；`foregroundServiceType = dataSync`。
- deleteIntent → DismissReceiver 复活（Android 13+ 可划掉的 FGS 通知）。

## 性能与稳定性清单

| 项 | 手段 |
| :--- | :--- |
| 大文件内存 | 全链路流式（分享 URI 流式读 / 接收 .part 流式写），峰值内存 ≈ 128 KiB 桶 |
| 可靠性 | 帧层 sha256 终验（`F`），不符由 PC 整文件重发一次 |
| 半开死链 | 双向心跳 + 45s 读超时看门狗 |
| 断线补发 | 出站引用队列 + 发送失败留队 + 重连清队 |
| 冻结恢复 | START_STICKY + BootReceiver + deleteIntent 复活 + 用户 nudge 全速重拨 |
| 交互延迟 | 分享/立即同步在服务未起时冷启动挂服务（探针已验证路径） |

## 权限与一次性授权仪式

```xml
POST_NOTIFICATIONS            <!-- 13+ 运行时；HyperOS 需手动设置开启 -->
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC  <!-- Android 14 强制类型声明 -->
INTERNET
MANAGE_EXTERNAL_STORAGE      <!-- 写 /storage/emulated/0/Airpipe -->
RECEIVE_BOOT_COMPLETED
```

装机后：①`adb shell appops set com.morethan.airpipe MANAGE_EXTERNAL_STORAGE allow`；②手动开通知权限；③省电策略「无限制」+ 自启动（PairActivity 内置分步引导页显示这三步）。

## 已知限制

- 校园网级大平面网链路层限杀互访流量 → 不可用也不解决（换热点即好）；广播不可达时用配对页手动 IP。
- 剪贴板「copy to clipboard 文件 URI」依赖文件管理器支持（HyperOS 自带实测支持；第三方 FM 不保证，分享面板是通用兜底）。
- 单 PC 场景；UI 不暴露多设备管理。

## 变更日志

- 2026-11-04 v1 初稿（审议中）
