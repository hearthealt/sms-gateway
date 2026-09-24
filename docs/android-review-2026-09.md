# Android 端审查：Bug 与样式布局（2026-09-24，基于 5e1181d / v1.0.4）

本次只读代码、未改动，也没有在真机上复现。文中路径相对于 `android/app/src/main/java/com/smsgateway/app/`，标注为 `文件:行号`。

**标 ✅ 的条目已对照源码逐行核实**；其余由审查时通读代码得出，动手修之前应先在代码里再确认一遍。

**总体结论**
- **数据链路**：没有找到确定会丢短信的缺陷。goAsync 的配对、多段短信拼接、Room 迁移、上传重试链都检查过，没有问题。
- **Bug 集中在两类**：
  - 状态或号码被悄悄弄错；
  - 扫码页与应用锁的交互。
- **样式**：颜色 token 执行得很好。问题集中在五处：
  - Material 主题没接字体和形状；
  - 浅色主题对比度不够；
  - 大字体下溢出；
  - 键盘（IME）遮挡；
  - 组���重复实现。

---

## 修复状态（2026-09-24）

下面每一条都对着上面的条目号。**本文档上半部分是审查当时的原文，未改动**，只在少数
「修法与建议不同」的地方加了说明。状态只有三种：

- **✅ 已修复** —— 改动已落在代码里，`gradle assembleDebug`、`assembleRelease`、
  `testDebugUnitTest` 全部通过（只剩 `HapticFeedback.kt` 里 3 条与本次无关的弃用警告）。
- **⚠️ 有取舍** —— 修了，但做法与本文档的建议不同，或带了明确的代价，理由见「说明」列。
- **⏸ 未做** —— 明确不做，理由见说明。

> **验证的边界**：这一轮只做到了「能编译、能打包、单测通过」。
> 样式的实际效果（对比度、1.3 倍字体下的折行、平板上限宽）**没有在真机上逐页看过**，
> 那需要另开一轮按上面「建议的修复顺序」第 3 条走。Bug 部分里 B3、B5、B8、B16
> 的行为变化也只有静态推理，没有现场复现。

### Bug

| # | 状态 | 落在哪里 / 说明 |
|---|---|---|
| B1 | ✅ 已修复 | `QrAnalyzer.reset()`；`CameraScanner` 多一个 `resetSignal` 参数（用 `LaunchedEffect` 打在**同一个** analyzer 实例上，不是 `remember(key)` 重建）。`QuickConnectScreen` 在 Invalid 分支与所有「重新扫一张」的路里把它 +1。 |
| B2 | ✅ 已修复 | `BootReceiver` 条件改为 `DevicePrefs.isRegistered && GatewayState.isRunning`，与 `ensureGatewayServiceRunning` 同源。 |
| B3 | ⚠️ 有取舍 | 新增 `DevicePhone.isSingleSim()`：有权限时以卡列表为准，**没权限时退回 `activeModemCount()`**（它不需要任何权限）。代价就是文档里说的那条：双卡槽只插一张卡会被当成双卡 → 号码留空（退化，不是错误）。另外首页加了「缺电话权限」横幅、自检页加了「电话权限」一项，两处都写清了「双卡机、且单卡机不受影响」。 |
| B4 | ✅ 已修复 | `AppLock.lockIfEnabled` 与权限申请一起挪进 `savedInstanceState == null` 分支。 |
| B5 | ⚠️ 有取舍 | 启用应用锁时 `FLAG_SECURE` + API 33 的 `setRecentsScreenshotEnabled(false)`，在 `onStart` 里跟随锁的开关（设置页一开锁就生效，不必等重启）。代价是文档里点出的那条：**锁开着时用户自己也截不了图**（FLAG_SECURE 会让截图变黑）。这是刻意接受的 —— 装了锁的机器本来就不该让人随手把验证码带出去，而关掉锁就恢复。 |
| B6 | ✅ 已修复 | 四处：`refreshNotifyChannels` 未注册直接返回并把渠道清成 null；`loadServerSmsNow` 未注册直接返回；`AuthInterceptor` 只对**带了令牌**的请求回调 401（见 B10）；`MetricsRow` 的「今日短信」未注册时不可点、`NotifyTestCard` 的按钮要求 `hasChannels`。 |
| B7 | ✅ 已修复 | DAO 的 `retryNow` 加 `AND status != 'uploaded'` 并返回受影响行数（0 就不叫 worker）；队列页改成订阅 Room Flow（`observeOutstanding`），`refreshQueueNow` 退化成「进页面读一次」。 |
| B8 | ✅ 已修复 | `startMonitoring()` → `suspend fun runPolling()`，由 `MainActivity` 的 `repeatOnLifecycle(STARTED)` 驱动；回到前台会从头跑一轮（顺带立刻刷新一次）。 |
| B9 | ✅ 已修复 | `SystemClock.elapsedRealtime()`。 |
| B10 | ✅ 已修复 | `AuthState.markTokenRejected(context, rejectedToken)` 先比对当前令牌；拦截器把**被拒的那一份**传下去；心跳与上传 worker 各自传「这次请求实际带上去的那一份」（worker 是在发请求前记下来的，不是收到 401 之后再读）。 |
| B11 | ✅ 已修复 | 全部可变字段 `@Volatile`；`configure` / `resetClient` / `ensureConfigured` / `updateToken` / `updateDeviceId` / `getHttpClient` / `getProbeClient` 全部 `@Synchronized`。 |
| B12 | ✅ 已修复 | `UploadAttempt` 带 `connectionFailed`；连接层失败时记完这一行就 `break` 出本轮。**只对连接层收手**，HTTP 5xx 仍继续下一条 —— 那说明对面活着，只是这一条有问题。 |
| B13 | ✅ 已修复 | `stop()` 里先 `liveInstance = null` 再 `stopService`（顺序不能反，`stopService` 是异步的）；顺手给 `liveInstance` 加了 `@Volatile`，因为 `stop` 现在会被 IO 线程调到。 |
| B14 | ✅ 已修复 | 键里加**发送方哈希**（不是原串 —— 原串是键里唯一无界的成分，会让长度逼近上限）。单测补了「同卡同秒同正文不同发送方」与「发送方为空」两条。 |
| B15 | ✅ 已修复 | `phone = CASE WHEN phone = '' THEN :phone ELSE phone END`。 |
| B16 | ✅ 已修复 | 导航状态（`screen` / `backStackRaw`）提到 `MainActivity` 的 `rememberSaveable`，位于锁屏判断**之上**。 |
| B17 | ✅ 已修复 | `serverSmsMutex` 串行化 `loadServerSmsNow`（用排队而不是「已在加载就返回」—— 后者会让一次下拉刷新被静默丢掉）。 |
| B18 | ✅ 已修复 | `IdleView` 里「去系统设置里开权限」常驻，不再依赖 `shouldShowRequestPermissionRationale` 的判断（它返回 false 时两种情况分不开）。 |
| B19 | ✅ 已修复 | 确认框的 `onDismissRequest` 与「取消」走同一个 `rescan()`。 |
| B20 | ✅ 已修复 | `queueLoading` 初值改 true，首次进页显示转圈。 |
| B21 | ✅ 已修复 | 去掉 `**`，那一块改用 `InlineNotice`。 |
| B22 | ✅ 已修复 | `snackbarHostState` 传进队列、服务端记录、重要日志、自检、扫码连接五页，各自在 `AppScreen(snackbarHost = ...)` 挂上。 |

### 真机确认 / 升级前注意

| 项 | 状态 | 说明 |
|---|---|---|
| `onCreate` 里 `startForeground` 无 try/catch | ✅ 已修复 | 抽成 `ensureForeground()`，接住之后 `stopSelf()` 并写一条新事件 `GATEWAY_START_BLOCKED`（「网关未能启动（系统限制）」）。单独一个事件类型而不并进 `GATEWAY_DESTROYED`：那一条是「起来之后被杀了」，这一条是「压根没起来」，下一步查的东西完全不同。 |
| 升到 35 之后 dataSync 的 6 小时超时、BOOT_COMPLETED 不能拉起 dataSync | ⏸ 未做 | 不适用：`targetSdk` 仍是 34。留档，等真要升的时候按这两条逐项处理 —— 这不是代码缺陷，是平台策略变更。 |

### 样式与布局

| 区块 | 状态 | 说明 |
|---|---|---|
| Material 主题接入 typography 与 shapes | ✅ 已修复 | `Typography` 只做映射（对话框标题从 24sp 落到 h2 的 20sp），`Shapes` 五档（extraLarge 28dp → 20dp）。 |
| 浅色主题对比度 | ⚠️ 有取舍 | 正文四级灰重排成一条连续阶梯（白底 12.4 / 11.0 / 7.2 / 5.3:1），`Faint` 提到 3.6:1（非文字门槛）。**`Success` 也跟着从 #2E7D32 改到 #1B5E20** —— 它原先压在自己的浅绿底上只有 4.2:1，而文档里没点到它。另外 Material 的 `primary` 从 `brand` 里分了出来：浅色 #1976D2（白字 4.6:1），深色 #64B5F6 + 深色前景 —— 原先两种主题共用一个 #1E88E5，白字压上去只有 3.7:1，按钮标签一直不达标。 |
| 字重层级 | ✅ 已修复 | `bodyMedium` → Normal；`h3` → 16sp SemiBold。 |
| `hint` 行高 | ✅ 已修复 | 13sp → 16sp。 |
| 图标/进度圈尺寸散写 | ✅ 已修复 | 新增 `AppSize`；`ButtonDefaults.IconSize` 那条改成了同一个值（18dp）的 `AppSize.iconSm` —— 不引入一个只在按钮里成立的常量。顺带删掉两个从没被用过的 token（`contentGap`、`metricLarge`）。 |
| `imePadding` + `windowSoftInputMode` | ✅ 已修复 | 清单里只加了 `adjustResize`（理由见下）。设置页与锁屏的滚动容器加了 `imePadding()`。**没有加 `configChanges`** —— 见下面的「刻意没做的事」。 |
| 锁屏状态栏图标看不见 | ✅ 已修复 | 给锁屏加了同一条品牌蓝顶栏（复用 `AppTopBar`），同时应用名改取 `R.string.app_name`。 |
| 顶栏标题无 weight/maxLines、高度写死 | ✅ 已修复 | `weight(1f, fill = false)` + `maxLines = 1` + 省略号；`height(56.dp)` → `heightIn(min = 56.dp)`。 |
| Snackbar 压在手势条上 | ✅ 已修复 | 主页那个 host 加了 `navigationBarsPadding()`。 |
| 页面硬切换 | ✅ 已修复 | `AnimatedContent` 做 180ms 淡入淡出（只做透明度：这些页面是并列的，左右滑会暗示一个不存在的层级）。 |
| 平板/横屏卡片拉满 | ✅ 已修复 | `AppScreen` 与主页内容都限 `widthIn(max = 640.dp)` 并居中 —— 收在 `AppScreen` 里一处，免得将来漏掉某一页。 |
| 首页「纸」的圆角看不到 | ✅ 已修复 | 外层 Box 铺一层 `AppColor.Brand`。它正好是渐变的下沿、而内容区的上边缘正好接在渐变的末尾，所以圆角处露出的颜色与上方无缝接上。 |
| 未注册时该做的事只是一行小字 | ✅ 已修复 | 未注册且未运行时，开关的位置直接换成实心的「扫码连接」按钮。 |
| 「设备被禁用」说三遍 | ✅ 已修复 | 删掉那条横幅，「检查状态」按钮搬进状态卡的禁用分支。 |
| 开关没有无障碍标签 | ⚠️ 有取舍 | 用 `semantics(mergeDescendants = true)` 把整块状态区合成一个读屏节点，**没有**做成「整行 toggleable」。理由：那块区域很大、上面还叠着标题与副标题，整行可点意味着误触任何一个位置都会启停网关 —— 而误触的代价是短信从此不再上报。触摸目标仍然只有开关本身。 |
| MetricsRow 改成三格 | ✅ 已修复 | 标签在上、数字在下，数字统一 h3。只有「待上传」「今日短信」可点（「今日验证码」是记录页的子集，没有独立页面，点了没反应比不点更糟）。 |
| IdentityRow 的值没有 weight | ✅ 已修复 | 与设置页的 `StatusRow` 合并成 `InfoRow`，值 `weight(1f, fill = false)` + End + 省略号，可点的行 `minHeight = 48dp`。 |
| MiniBarChart 四个问题 | ✅ 已修复 | 标题改用 bodyMedium（原来比读数还小）；峰值那行**始终占位**（不再是条件渲染，点柱子不再跳）；24 根柱子合并成一个读屏节点 + 「下一根柱子」自定义动作；轴标签行高按字号**量出来**（写死的 16dp 在 1.3 倍字体下会裁字），占位骨架用同一个值。 |
| 三种列表行各写各的 | ✅ 已修复 | 统一 16dp 内边距、`bodyLarge` 发送方 / `bodyMedium` 正文、`StatusBadge`、`formatClockTime` 那套时间格式（服务端记录页原先显示完整的 `yyyy-MM-dd HH:mm:ss`，加了一个 `formatServerTime` 做换算）。 |
| 验证码是普通正文字 | ✅ 已修复 | 改成芯片：InfoBg 底、等宽 h3、复制图标、`minimumInteractiveComponentSize()` 撑到 48dp。 |
| QueueRow 阴影 / 多一个 Spacer | ✅ 已修复 | 阴影交给 `AppCard` 统一（一律 0 投影）；徽章后面那个 Spacer 删掉。 |
| 空状态按钮与图标 | ✅ 已修复 | 队列页与服务端记录页的空状态去掉「返回主页」按钮；日志页空状态图标从 Refresh 换成 History。 |
| 刷新失败只是一行红字 | ✅ 已修复 | 换成 `InlineNotice(Danger)` + 一个就地「重试」。 |
| 设置页 padding 顺序 | ✅ 已修复 | `.padding(padding).verticalScroll().padding(gutter).imePadding()`。 |
| 「读 SIM 卡」与「保存手机号」并排被截断 | ✅ 已修复 | 读卡收进输入框的 `trailingIcon`，下面只留一个整宽的「保存手机号」。 |
| 「其他」是一堵按钮墙 | ✅ 已修复 | 改成三行列表（带图标、说明、箭头），两个删除动作染 Danger 色并**各自加确认弹窗**（原先点下去就删，不可撤销）。 |
| AppLockCard 三处 | ✅ 已修复 | 读屏语义合并；开关颜色与首页统一；卡片标题与行标题重复 → 行标题改成直接说当前状态；PIN 错误改用 `isError` + `supportingText`。 |
| 自检页两张卡内边距不同 / 顶部无进度 / 未通过项没有下一步 | ✅ 已修复 | 两张卡统一 0 内边距 + 16dp 条目内边距；已有结果时再点刷新顶部出细进度条；未通过项右侧给「去开启 / 去设置 / 去连接」（`SelfTestAction` 枚举，落在 `SelfTestItem.action` 上，不让界面按中文 label 猜）。 |
| 锁屏（滚动、imePadding、Done、自动聚焦、logo、指纹、居中、应用名、死代码） | ✅ 已修复 | 逐条都改了；`LockBusy()` 已删。指纹在进页面时自动拉一次（失败回到这一页，文字按钮仍在，不会变成死路）。 |
| 扫码/导出页 | ✅ 已修复 | 连接成功加了「返回主页」；导出的口令警告改成 `InlineNotice`（不再是等宽体），原文折到「显示原文」后面；取景加了扫描框；`IdleView` 文字居中；`⚠` 字符换成图标。 |
| 重复组件合并 | ✅ 已修复 | `AppCard`（现在 9 处卡片都走它）、`CheckResultRow`（自检项 + 转发渠道）、`InfoRow`（合并并删掉 `StatusRow`）、`InlineNotice`（5 处行内提示）、`ChevronIcon`（合并两处箭头）、`RefreshableScreen`（三个列表页的下拉刷新外壳）。 |

### 刻意没做的事

| 项 | 为什么不 |
|---|---|
| 清单里加 `android:configChanges` | 试过又回退了。重建本身是无害的，有害的是重建时**无条件上锁**与**无条件申请权限** —— 那两条已经在 B4 里修掉，`configChanges` 是在解决一个不存在的问题。而声明它之后，Configuration 的变化完全交给 Compose 自己去发现，一旦哪条路径没接上（字号、深浅色都走过这种坑），表现就是「改了系统设置界面没变」，比多一次重建难查得多。 |
| HeroCard 整行 `toggleable` | 见上面 B 表格里的说明：误触会停掉网关。 |
| `AppListCard` / `InfoRow` 的进一步抽象 | 现有的 `AppCard + InfoRow` 已经覆盖了那 9 处卡片与 4 处标签-值行；再抽一层（比如把列表卡与设置卡分开）只会让「这一处该用哪个」变成新的问题，而收益是省下几行。 |

---

## 一、Bug

### 高

#### B1 ✅ 扫错一次码之后，扫码页就不再识别
- **位置**：`qr/CameraScanner.kt:37`、`qr/QrAnalyzer.kt:50`、`qr/QuickConnectScreen.kt:98-113`
- **原因**：
  - `remember { QrAnalyzer(onDecoded) }` 没有 key。analyzer 解出第一段文字后，`finished` 就永久置为 true。
  - `offerConfig` 在扫到非配置码时，先写 `scanning = false`，紧接着又写回 `true`。两次写入之间不会发生重组，所以 CameraScanner 不会移出组合，停了工的 analyzer 一直留着。
- **触发**：首次配网时先对准了别的二维码（比如微信码），页面提示「扫到的不是配置二维码」；再对准正确的码，预览照常在动，但永远��有反应。只能退出页面重进。
- **修法**：给 `remember` 加一个每次重新扫码时递增的 key；或者让 analyzer 暴露 `reset()`，在 Invalid 分支里调用。

### 中

#### B2 ✅ 开机或覆盖升级时无视用户的「停止网关」
- **位置**：`receiver/BootReceiver.kt:27`
- **原因**：只判断 `DevicePrefs.isRegistered`，不看 `GatewayState.isRunning`。服务的 `onCreate` 随后调 `markStarted`，把运行态重新写回 true，并排上传任务。而 `DashboardViewModel.ensureGatewayServiceRunning` 是看运行态的，两处判断标准不一致。
- **触发**：用户点了「停止网关」（界面承诺短信只留在本地、不上报），之后手机重启，或推了一次 APK 更新（`MY_PACKAGE_REPLACED`），积压的短信被全部自动上报。
- **修法**：条件改为 `isRegistered && GatewayState.isRunning`。`gateway_running` 在关机时不会被清掉（关机时不走 `onDestroy`），所以可以直接依赖。

#### B3 ✅ 没有电话权限时，单卡机收到的短信号码也全为空
- **位置**：`receiver/SmsReceiver.kt:320, 339-341`；`util/DevicePhone.kt`（`isKnownSubscription`、`querySlots`）
- **原因**：
  - 没有 READ_PHONE_STATE 时，`isKnownSubscription` 返回 false，于是 subId 变成 -1。
  - 进入 subId < 0 分支后，`querySlots` 带回 problem「未授予电话权限」，`singleSim` 恒为 false，函数��接返回 ""。
  - 结果是设置页手填的号码永远用不上。
  - API 33+ 上 READ_PHONE_NUMBERS 和 READ_PHONE_STATE 缺任何一个，都会走到同一结果。
- **触发**：单卡机上用户拒绝了电话权限，又手填了号码。之后每条验证码都以 `phone=""` 上传，服务端跳过 `sms:code:{号码}` 缓存，按号码等码的调用方全部超时。设备侧却显示「上传成功」，`SMS_NO_PHONE` 每个进程也只记一次。
- **说明**：这是 336-338 行注释里的刻意取舍，但它和 349-352 行「一律留空就是功能全废」的判断自相矛盾。
- **修法（待定）**：
  - 可选方案：改用不需要权限的 `TelephonyManager.getActiveModemCount()` 判断卡槽数。代价是双卡槽机只插一张卡时仍被当作双卡。
  - 至少要做到：在首页和自检页明确提示「缺电话权限，号码无法写入」。

#### B4 ✅ 转屏、切换深色模式、改字号都要重输 PIN
- **位置**：`MainActivity.kt:74`
- **原因**：每次 `onCreate` 都无条件调用 `AppLock.lockIfEnabled`，清单里也没有配置 `configChanges`。注释说的「只在冷启动时上锁」没有做到。
- **修法**：只在 `savedInstanceState == null` 时上锁（进程被杀后重建时同样会上锁，这是期望的行为）。

#### B5 最近任务的缩略图直接露出验证码
- **位置**：`MainActivity.kt`，全文没有 `FLAG_SECURE` 或 `setRecentsScreenshotEnabled(false)`
- **触发**：离开时停在服务端记录页，或停在导出二维码页（这一页带接入口令）。路过的人打开最近任务就能看到缩略图，不需要解锁，而这正是 AppLock 要防的场景。
- **修法**：启用应用锁时设置 `window.setFlags(FLAG_SECURE)`，API 33+ 用 `setRecentsScreenshotEnabled(false)`。

#### B6 未注册设备会被误报「服务端已不认这台设备」，还会写一条 ERROR 日志
- **位置**：
  - `DashboardViewModel.kt:1267`：自检时总会调用 `refreshNotifyChannels`。
  - `DashboardViewModel.kt:1099`、`:1202`：`loadServerSmsNow` 不检查是否已注册。
  - `ui/screens/home/MetricsRow.kt:87`：「今日 N 条」入口一直可以点。
  - `ui/screens/selftest/NotifyTestCard.kt:84`：渠道列表为 null 时按钮仍可点。
- **原因**：没有令牌时请求不带 Authorization 头，后端返回 401。`AuthInterceptor` 于是调用 `AuthState.markTokenRejected`，后者会：
  - 停掉服务；
  - 写一条 `DEVICE_TOKEN_REJECTED`；
  - 在界面上提示去重新注册。
- **触发**：新装后还没注册就点「自检」或「今日 N 条」；或者改过服务器地址、令牌被清空之后。
- **修法**：在这些入口处先判断是否已注册；另外，没带令牌时收到的 401 不应该走 `markTokenRejected`。

#### B7 ✅ 队列页点「立即重试」会把已上传的短信再传一遍
- **位置**：`database/SmsQueueDao.kt:61`、`ui/screens/queue/QueueScreen.kt:71`、`DashboardViewModel.kt:977`
- **原因**：队列页的数据是进页面时拍的快照；`retryNow` 只写了 `WHERE id = :id`，不看当前状态。
- **触发**：停在队列页的时候，后台的 worker 已经把这条传上去了，但这一行还留在页面上。这时点「立即重试」，它会被改回 pending 再上传一次。服务端的 `duplicate_count` 加 1，管理端显示成「重复」。
- **修法**：条件改为 `WHERE id = :id AND status != 'uploaded'`；队列页改为订阅 Flow。

#### B8 应用在后台时轮询不停
- **位置**：`DashboardViewModel.kt:512-541`
- **原因**：`startMonitoring` 跑在 `viewModelScope` 里，不感知生命周期。按 Home 键，或在 Android 12+ 上按返回退回桌面，ViewModel 都还活着；前台服务又让进程常驻。于是一直每 5 秒查一次库、每 30 秒拉一次 `mySmsStats`，和心跳重复。
- **修法**：由 UI 层用 `repeatOnLifecycle(STARTED)` 驱动轮询；或者把 `StateFlow` 改成 `stateIn(WhileSubscribed(5000))`。

### 低

| # | 位置 | 问题 |
|---|---|---|
| B9 ✅ | `MainActivity.kt:124,130` | 用 `currentTimeMillis` 算离开时长。离开后把系统时间往前调，差值变成负数，回来时永远不会上锁。应改用 `SystemClock.elapsedRealtime()` |
| B10 | `network/AuthInterceptor.kt:45`、`util/AuthState.kt:37-54` | 任何一次 401 都会无条件清掉令牌并停网关，不核对被拒的是不是当前令牌。重新注册那一刻，如果还有请求在路上，它迟到的 401 会删掉刚存下的新令牌 |
| B11 | `network/RetrofitClient.kt:46-51, 94-98, 186-201` | `configure` 和 `resetClient` 没加锁，字段也不是 volatile。改服务器地址时如果和 `getApiService` 并发，可能把用旧地址构建的客户端写回去，之后一直连旧服务器 |
| B12 | `worker/SmsUploadWorker.kt:175-261` | 遇到网络错误（TRANSIENT）不提前退出，仍逐条等连接超时。服务器不可达且积压约 20 条以上时，这一轮会超过 WorkManager 的 10 分钟上限被系统中止 |
| B13 | `service/GatewayForegroundService.kt:75-89, 311-319` | `stop()` 没有清 `liveInstance`，所以每次手动停止都会多记一条 WARN `GATEWAY_DESTROYED`，干扰判断「是不是被 MIUI 杀了」 |
| B14 | `util/LocalMessageId.kt:34-35` | 去重键不含发送方：同一张卡、同一秒、正文相同、发送方不同的两条短信会被当成重复丢掉（概率很低） |
| B15 | `database/SmsQueueDao.kt:89-90` | `backfillIdentity` 会把号码统一覆盖成配置号码，抹掉按卡解析出���号码。目前基本走不到，以后启用时会重新引入双卡错标 |
| B16 | `MainActivity.kt:81-104` | 上锁时 GatewayApp 整个移出组合，导航栈和扫码连接的结果都会丢失，解锁后总是回到主页 |
| B17 | `DashboardViewModel.kt:1177-1227` | 服务端记录页刷新第 1 页和「加载更多」之间没有互斥，最终列表可能缺中间几页 |
| B18 | `qr/QuickConnectScreen.kt:116-136, 393` | 相机权限选了「不再询问」之后，「重新申请」按钮没有任何作用，也没有跳到系统设置的入口 |
| B19 | `qr/QuickConnectScreen.kt:200` | 扫到正确的码、弹出确认框后，点框外关掉，页面会显示「需要相机权限」，但实际上权限是有的 |
| B20 | `ui/screens/queue/QueueScreen.kt:106` | 第一次进入没读 loading 状态，会先闪一下「全部已上传」 |
| B21 ✅ | `ui/screens/selftest/NotifyTestCard.kt:73` | 文案里写了 `**没有启用**`，界面上原样显示出两对星号 |
| B22 | `MainActivity.kt:231-236` | `registerMessage` 用的是共享的 snackbarHostState，但队列、服务端记录、扫码、日志这几页没有挂这个 host，消息要回到主页或设置页才弹出 |

### 需要真机确认 / 升级前注意
- `GatewayForegroundService.kt:132` 在 `onCreate` 里调用 `startForeground`，外面没有 try/catch。在 Android 12+、没有电池白名单的情况下，START_STICKY 重新拉起服务时可能抛 `ForegroundServiceStartNotAllowedException`，导致进程崩溃。
- 目前 targetSdk 是 34，暂时没有影响。一旦升到 35：
  - dataSync 类型的前台服务会有 6 小时超时；
  - BOOT_COMPLETED 不能再拉起 dataSync 前台服务，开机后网关不会自动起来，而 BootReceiver 的 catch 会把这个失败吞掉，不留痕迹。

---

## 二、样式与布局

### 全局 / 设计系统

| 优先级 | 位置 | 问题 | 改法 |
|---|---|---|---|
| 高 ✅ | `ui/theme/AppTheme.kt:25` | 只传了 colorScheme，没传 typography 和 shapes。结果：<br>• 对话框标题是 24sp，比页面标题 20sp 还大，圆角 28dp；<br>• 按钮用默认的 14sp 胶囊形。<br>`ButtonShape`、`cardGap/sectionGap/gutter/contentGap`、`metricLarge` 这些 token 定义了但没人用 | 补上 `typography = Typography(...)` 和 `shapes = Shapes(medium=12, large=14, extraLarge=20)`；没用上的 token 要么用起来，要么删掉 |
| 高 | `ui/theme/AppColor.kt` | 浅色主题对比度不达标：<br>• InkMuted `#90A4AE` 在白底上约 2.6:1，却大量用于说明文字；<br>• Warning `#E65100` 约 3.4–3.8:1；<br>• Brand `#1E88E5` 配白字约 3.7:1；<br>• `AppStyle.kt:105`、`HeroCard.kt:124/131` 还叠了 α0.6–0.75 | InkMuted 改为 `#687883` 左右；Warning 改为 `#BF360C`；浅色主题的 primary 改为 `#1976D2`；去掉 alpha，改用实色的次级色 |
| 高 | `ui/theme/AppTypography.kt:42-46` | 字重层级是反的：bodyMedium 用 Medium，bodyLarge 反而是 Normal，所以短信正文比发送方还粗。另外 h3 和 bodyLarge 同为 15sp，卡片标题压不住正文 | bodyMedium 改为 Normal；h3 改为 16sp SemiBold |
| 中 | `ui/theme/AppTypography.kt:72-76` | hint 是 11sp、行高 13sp，中文折行会挤在一起 | 行高改为 16sp |
| 中 | 多处 | 图标尺寸有 16/18/20/22/24/28dp，进度圈有 16/18/28/32/36dp，都是散写的 | 新增 `AppSize` token；按钮内图标用 `ButtonDefaults.IconSize` |

硬编码最多的文件：
- `qr/QuickConnectScreen.kt`（143、288、374、387、404、408-409、439、448-451、475、482）：`Color.White`、`10.dp`、`6.dp` 这些不在网格上的值；
- `ui/screens/settings/SettingsScreen.kt`（205、233、288、342）；
- `ui/screens/home/IdentityRow.kt`（43、56）；
- `qr/QrExportScreen.kt`（64-65）。

### 系统栏 / 插入区域 / 导航

| 优先级 | 位置 | 问题 | 改法 |
|---|---|---|---|
| 高 ✅ | 全工程 | 没有一处 `imePadding()`，清单里也没设 `windowSoftInputMode`。edge-to-edge 下，设置页的号码框、锁屏的 PIN 框和解锁按钮都会被键盘盖住 | 这些滚动容器加上 `.imePadding()`；清单里加 `adjustResize` |
| 高 | `MainActivity.kt:61`、`ui/lock/LockScreen.kt` | 状态栏图标被钉成白色，而锁屏背景是浅灰 `#F4F6FA`，状态栏图标看不见 | 锁屏加同款蓝色顶栏；或者锁定时把 `isAppearanceLightStatusBars` 切成 `!dark` |
| 高 | `ui/AppStyle.kt:77, 93-107` | 顶栏标题和副标题都没有 weight 和 maxLines。1.3 倍字体下会挤掉右侧的操作按钮；高度写死为 56dp | 标题改为 `weight(1f, fill=false)` + `maxLines=1` + `Ellipsis`；高度改为 `heightIn(min=56.dp)` |
| 中 | `ui/screens/home/HomeScreen.kt:113-116` | Snackbar 会压在手势条上 | 加 `.navigationBarsPadding()` |
| 低 | `MainActivity.kt:238` | 页面之间是硬切换 | 用 `AnimatedContent` 做 fade 或 slide |
| 低 | `ui/AppStyle.kt` | 平板或横屏时卡片被拉满全宽 | 内容加 `widthIn(max=640.dp)` 并居中 |

### 首页

- **高** — `HomeScreen.kt:66-71`：顶部圆角「纸」效果实际看不到。圆角外面露出的窗口底色和 Surface 同色。
  - 改法：外层背景改用 Brand 色，或让 Surface 上移约 20dp，压在渐变头上。
- **高** — `HeroCard.kt:127-133`：未注册时，唯一该做的事（扫码连接）只是一行 11sp、α0.6 的提示字，开关又是灰的。
  - 改法：未注册时，在开关的位置放一个实心的「扫码连接」按钮。
- **中** — 「设备被禁用」重复说了三遍：`HomeBanners.kt:26-32` 的横幅，加上 `HeroCard.kt:174-180` 的标题和副标题。
  - 改法：把「检查状态」按钮挪进状态卡的禁用分支，删掉横幅。
- **中** — `HeroCard.kt:137`：开关没有无障碍标签，TalkBack 只会读「开关，已开启」。
  - 改法：整行做成 `toggleable(role = Role.Switch)`。
- **中** — `MetricsRow.kt:62-99`：大字体下右半部分折成两行，左右两边数字的样式也不一致。
  - 改法：做成三格统计（待上传 / 今日短信 / 今日验证码），标签在上、数字在下，数字统一样式。
- **中** — `IdentityRow.kt:98-102`：值没有 weight。24 位设备 ID 在大字体下会挤掉右侧箭头；行高约 44dp。
  - 改法：值加 `weight(1f)`、`End` 对齐、`Ellipsis`；行加 `heightIn(min=48.dp)`。
- **中** — `MiniBarChart.kt`，四个问题：
  - 标题比读数还小，层级反了（167/68 行）；
  - 点中峰值那根柱子时，「最多 …」一行消失，整页往上跳（257-264 行）；
  - 24 根柱子都是没有标签的 clickable，TalkBack 会连读 24 个「未加标签的按钮」（192 行）；
  - `LABEL_HEIGHT = 16.dp` 写死，大字体下轴标签会溢出（41 行）。
- **低** — `HomeScreen.kt:106`：多出一个 Spacer，身份卡上方的间距实际是 40dp。

### 列表页（队列 / 重要日志 / 服务端记录）

- **高** — `ServerSmsRow.kt:88-98`：验证码是这一页最重要的信息，却是普通正文字，点击区域约 22dp，也看不出能点。
  - 改法：做成芯片（InfoBg 底、等宽 h3 字号、带复制图标），并加 `minimumInteractiveComponentSize()`。
- **中** — 三种列表行各写各的：

  | | 内边距 | 正文 | 状态显示 | 时间格式 |
  |---|---|---|---|---|
  | QueueRow / EventLogRow | 16dp | bodyMedium 3 行 | StatusBadge | `formatClockTime` |
  | ServerSmsRow | 12dp | bodySmall 2 行 | 彩色小字 | 完整的 `yyyy-MM-dd HH:mm:ss` |

  - 改法：统一 16dp 内边距、同一种正文样式、StatusBadge、`formatClockTime`。
- **中** — `QueueRow.kt:68, 80`：
  - 失败的卡片加了 1dp 阴影，和其他卡片的无阴影风格冲突；
  - 徽章后面多一个 Spacer，徽章和发送方之间约 24dp。
- **中** — 空状态：
  - `QueueScreen.kt:114`、`ServerSmsScreen.kt:217`：实心的「返回主页」按钮和左上角的返回键重复，还是整页最显眼的元素；
  - `EventLogScreen.kt:121`：空状态图标用了 Refresh，看起来像一个按钮。
- **低** — `ServerSmsScreen.kt:290`：刷新失败只是一行红色小字，太弱。

### 设置页

- **中 ✅** — `SettingsScreen.kt:134-136`：`.padding(md)` 写在了 `verticalScroll` 之前，滚动时内容在离边缘 16dp 处被硬切。
  - 改法：顺序改为 `.padding(padding).verticalScroll().padding(md).imePadding()`。
- **中** — `SettingsScreen.kt:205-252`：「读 SIM 卡（2）」和「保存手机号」两个按钮并排，1.3 倍字体下文字被截断。
  - 改法：「读 SIM 卡」改为输入框的 trailingIcon，下面只留一个「保存」按钮。
- **中** — `SettingsScreen.kt:297-329`：「其他」卡片是三个同等分量的描边按钮，堆成一堵墙。
  - 改法：改成列表行。两个清理项用 Danger 色，并加确认弹窗。
- **中** — `AppLockCard.kt:49-73`：
  - 开关同样缺语义；
  - 颜色和首页的开关不一致；
  - 卡片标题「应用锁」和行标题「锁定界面」重复。
- **低** — `AppLockCard.kt:173-175`：PIN 错误是单独一行 Text。
  - 改法：用 `OutlinedTextField(isError, supportingText)`。

### 自检页

- **高 ✅** — `NotifyTestCard.kt:73`：界面上直接显示出 Markdown 星号（同 B21）。
- **中** — 同一页两张卡片内边距不同：`SelfTestCard.kt:47-52` 用 16/12dp，NotifyTestCard 用 20dp，标题左边对不齐。另外 `NotifyTestCard.kt:151` 的 `Spacer(0.dp)` 加上 spacedBy，底部实际留白是 32dp。
- **中** — 已有结果时再点刷新，只有图标变灰，看不出正在自检。
  - 改法：顶部加一条 `LinearProgressIndicator`。
- **低** — 未通过的项没有下一步操作。
  - 改法：在右侧加「去开启」之类的按钮。

### 锁屏（`ui/lock/LockScreen.kt`）

- **高**：
  - 内容不能滚动，也没有 imePadding，键盘弹出后解锁按钮被挡住（78-85 行）；
  - PIN 输入框没有 `ImeAction.Done` 触发解锁；
  - 进页面不会自动聚焦到 PIN 框。
- **中**：
  - 没有 logo 和锁图标；
  - 指纹入口太弱，有指纹时应该进页面就自动拉起一次；
  - 说明文字折行后不居中（88-92 行）；
  - 应用名写死在代码里（86 行），应改用 `R.string.app_name`。
- **低**：`LockBusy()`（174 行）是死代码。

### 扫码 / 导出页

- **中** — `QuickConnectScreen.kt:462-469`：连接成功后没有「返回主页」按钮。
- **中** — `QrExportScreen.kt:102-114`：
  - 中文警告用了等宽字体；
  - 含口令的原文直接明文展示，应折叠到「显示原文」后面。
- **低**：
  - 取景画面没有扫描框；
  - IdleView（391 行）的文字没有居中；
  - 221 行用 `⚠` 字符，而其他页面用 Warning 图标。

### 可以合并的重复组件

1. **卡片外壳**：9 处手写 `Card(...)`，内边距有 12/16/20 三种，elevation 有的写了有的没写。
   - 改法：统一成 `AppCard(contentPadding)` / `AppListCard`。
2. **状态表达**：5 套写法（StatusBadge、彩色小字、图标 +「已送达 / 失败」、图标 + 彩色说明、彩色汇总）。
   - 改法：��态标签统一用 StatusBadge；自检和转发的检查项抽成 `CheckResultRow(ok, title, detail)`。
3. **标签-值行**：`StatusRow`（设置页）和 `IdentityLine`（首页）是同一种东西。
   - 改法：合并成 `InfoRow`，值统一 `weight(1f)` + End 对齐 + 省略号。
4. **行内提示 / 警告**：5 处各写各的。
   - 改法：统一成 `InlineNotice(type, text, action?)`。
5. **零碎重复**：
   - 箭头图标在两处各写了一份；
   - 下拉刷新列表的外壳在三个列表页里复制了三遍。

---

## 三、建议的修复顺序

1. **小改动、影响明确**：
   - B1：analyzer 复位；
   - B2：开机时检查运行态；
   - B4：只在首次创建时上锁；
   - B7：`retryNow` 加状态条件；
   - B9：改用 `elapsedRealtime`；
   - B21：去掉星号；
   - `imePadding`、锁屏状态栏、设置页 padding 顺序。
2. **需要先定方案**：
   - B3：没有电话权限时的号码回落；
   - B5：防截屏会连带禁止用户自己截图；
   - B6：未注册时收到 401 的处理；
   - B8：轮询改为跟随生命周期。
3. **单独开一轮，要在真机上逐页看效果**：
   - 主题接入 typography 和 shapes；
   - 对比度调整；
   - 字重层级；
   - 首页和列表行的重排；
   - 组件合并。
