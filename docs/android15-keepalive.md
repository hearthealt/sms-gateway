# Android 15 的保活限制：会影响什么、怎么应对

**调查时间 2026-09-24。** 当前 `targetSdk` 是 **34**，所以这些限制**还没生效**——
它们是「目标 API 35 及以上的应用」的行为变更。这份文档是为了在真要升上去时不至于
从头查起，**不代表现在就要动代码**。

依据：Android 官方 [前台服务的变更](https://developer.android.google.cn/develop/background-work/services/fgs/changes)
与 [Android 15 行为变更](https://developer.android.com/about/versions/15/behavior-changes-15)。

---

## 一、两条限制

| 限制 | 具体行为 |
| --- | --- |
| `dataSync` 前台服务**每天 6 小时** | 应用在 24 小时内的 `dataSync` 前台服务累计运行满 6 小时后，系统调用 `Service.onTimeout(int, int)`，服务必须在几秒内 `stopSelf()`，否则 **ANR**（"Foreground service did not stop within its timeout"）。`onTimeout` 在 Android 14 及以下不存在 —— 那种设备上到点直接把应用置为 cached。 |
| 开机广播**不能拉起** `dataSync` 前台服务 | `BOOT_COMPLETED` 接收器尝试启动会被禁止的前台服务类型时，系统抛 `ForegroundServiceStartNotAllowedException`。`dataSync` 在禁止之列。 |

`mediaProcessing` 类型同样有 6 小时限制。**`specialUse` 不在被限制的类型里。**

---

## 二、对本应用的实际影响

先说结论：**短信不会丢，但「无人值守也能传上去」会退化成「要有人打开应用」。**

`GatewayForegroundService` 的类型是 `dataSync`，所以两条都打得到它。而它承载的是：

- 每 30 秒一次的心跳（在线状态、指令下发、外发短信下发都挂在心跳上）
- 每 5 分钟一次的补传（把断掉的重试链重新点起来）
- 每小时一次的事件日志剪枝

**不依赖它的**（这是关键，决定了改造是「降级」而不是「重写」）：

- `SmsReceiver` 是**清单里声明的广播接收器**，进程不在系统也会为它拉起来 ——
  短信照收、照入库。
- 上报走 `WorkManager`，它的开关是持久化的 `gateway_running`，与界面、与服务的存活都无关。

**但有一个耦合点必须点出来：** `SmsUploadWorker` 开头就判
`if (!GatewayState.isRunning(context)) return Result.success()` —— 也就是说
**「网关没在跑」= 短信只入库、不上报**（这是产品承诺：界面上说「短信会留在本地，不会上报」）。

于是两条限制各自的后果是：

| 场景 | 会发生什么 |
| --- | --- |
| **开机后** | `BootReceiver` 那句 `startForegroundService` 抛 `ForegroundServiceStartNotAllowedException`。它已被 try/catch 包住并记 `GATEWAY_START_BLOCKED`，所以**现场看得见**。此时 `gateway_running` 仍是 true（没人把它改成 false），于是**上报照常**，只是没有心跳 —— 后台显示离线，但短信照传。 |
| **6 小时用满后** | 服务被停。`onDestroy` 会把 `GatewayState` 置为 false，于是**上报也跟着停**——短信只入库不上传，直到有人打开应用（`ensureGatewayServiceRunning` 会把服务拉回来）。**这是真正会疼的那一条**：一台无人值守的设备会在每天剩下的 18 小时里攒着不上报。 |

---

## 三、候选方案

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| **A. 换 `specialUse`**（推荐） | 清单里把 `foregroundServiceType` 从 `dataSync` 改成 `specialUse`，并加 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途 | 改动最小（一个属性 + 一句说明），且**不受那两条限制**。代价：上架 Google Play 需要提交用途说明并可能被审；本项目是侧载分发，这条不构成阻碍。**没有任何功能损失。** |
| B. 拆成短时任务 | 前台服务只做短时 `dataSync`，心跳改由 WorkManager 周期任务承担 | 保住「收 + 上报」，但**心跳最细只能到 15 分钟**（WorkManager 周期任务的下限），于是「设备离线」的判定（90 秒窗口）彻底失效、指令与外发短信的下发延迟从 30 秒变成 15 分钟。对一台挂着看现场的设备来说这是明显退化。 |
| C. 干脆不要前台服务 | 去掉 FGS 与「网关开关」，采集靠广播、上报靠 WorkManager、心跳靠周期任务 | 架构最简（少一个常驻服务、少一个 `gateway_running` 概念）。代价是**产品语义变了**：「停掉网关短信就不上报」这个承诺没有了，而那是设置页与主页都在说的东西。要动的话得先和用户确认这条承诺还要不要。 |
| D. 什么都不做（现状） | 停在 `targetSdk 34` | 只要不升 `targetSdk`，限制就不生效；而本项目不上架（Play 从 2025-08-31 起要求新应用与更新目标 API ≥ 35），所以没有外部力量逼着升。**代价是「哪天想升就得先解决这件事」**，而那时候的排查成本比现在高。 |

**推荐：现在不动代码（D），真要升 35 时走 A。** 理由：A 的成本只有一行属性，
而它换掉的是一整类问题；D 之所以现在成立，是因为我们不上架、也没有别的理由升。

---

## 四、怎么在真机上确认（不用真的升 targetSdk）

Android 15 设备上可以**强制打开**这两条限制来提前验证，不必改 `targetSdk`：

```bash
adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.yunyi.smshub
adb shell am compat enable FGS_SAW_RESTRICTIONS com.yunyi.smshub
# 不重启也能发一条开机广播：
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED com.yunyi.smshub
```

验证时看两处：设备端事件日志里有没有 `GATEWAY_START_BLOCKED`（开机那条），
以及**6 小时之后**上传是否停下（那一条要等，或者把 `dataSync` 的额度用别的办法耗掉）。

> ⚠️ 这两条只是把限制打开，**不模拟 targetSdk 的差异**。真要确信，还是得把
> `targetSdk` 提到 35 再跑一遍 —— 所以真要验证时，那一步本身就该在一个独立分支上做。

---

## 五、如果决定走 A（specialUse），改动清单

1. `AndroidManifest.xml`：`android:foregroundServiceType` 改成 `specialUse`，
   并加 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…"/>`。
2. `targetSdk` 提到 35；同时确认 `RECEIVE_SMS` 等权限没被新的前台服务类型要求带累
   （`specialUse` 不需要额外权限）。
3. 重新过一遍**开机拉起**那条路径：`BootReceiver` 现有的 try/catch 与
   `GATEWAY_START_BLOCKED` 事件保留 —— 即便换了类型，别的 ROM 限制依旧可能抛。
4. 补一条自检项（可选）：在自检页显示「前台服务类型」与当天已用时长，便于现场判断
   是不是撞上了系统限额。
