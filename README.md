# SMS Gateway 短信网关

把一台 Android 手机变成短信接收网关：手机端 App 监听本机收到的短信并上报到服务端，服务端按规则筛选、提取验证码，外部调用方通过 API Key 拉取或阻塞等待验证码。

典型场景：某个服务需要一个专属的短信接收通道，把一台闲置 Android 手机插上 SIM 卡放在有网的地方，即可通过 HTTP 接口取到该号码收到的验证码，无需自建短信猫或申请运营商通道。

---

## 目录

- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [对外 API（API Key 鉴权）](#对外-apiapi-key-鉴权)
- [设备 API（deviceToken 鉴权）](#设备-apidevicetoken-鉴权)
- [管理后台 API](#管理后台-api)
- [短信采集规则](#短信采集规则)
- [消息转发（v2）](#消息转发v2)
- [配置说明](#配置说明)
- [设备端排查](#设备端排查)
- [安全注意事项](#安全注意事项)
- [开发与测试](#开发与测试)

---

## 系统架构

```
┌──────────────────┐        上报短信 / 注册 / 心跳        ┌─────────────────────┐
│  Android App     │ ──────────────────────────────────▶ │   Backend           │
│  (短信网关客户端)│ ◀────────────────────────────────── │   Spring Boot 3.2   │
│                  │        设备状态 / 今日统计            │                     │
└──────────────────┘                                     └──────┬───────┬──────┘
                                                                 │       │
                                    拉取 / 阻塞等待验证码        │       │
┌──────────────────┐    Authorization: Bearer sk-xxx           │       │
│  外部调用方      │ ──────────────────────────────────────────▶│       │
│  (业务系统)      │ ◀──────────────────────────────────────────┘       │
└──────────────────┘                                                     │
                                                                          ▼
┌──────────────────┐                                     ┌─────────────────────┐
│  管理后台        │ ──────────────────────────────────▶ │  MySQL 8  +  Redis 7│
│  (Vue 3 + Vite)  │        设备 / 短信 / 规则 / 密钥      │                     │
└──────────────────┘                                     └─────────────────────┘
```

三类调用方走三套独立的鉴权拦截器，路径完全隔离：

| 调用方 | 路径 | 凭证 | 拦截器 |
| --- | --- | --- | --- |
| Android 设备 | `/api/device/**`、`/api/sms/receive` | deviceToken（注册时下发） | `DeviceAuthInterceptor` |
| 外部业务系统 | `/api/sms/wait`、`/api/sms/list` | API Key（管理后台签发） | `ClientAuthInterceptor` |
| 管理后台 | `/api/admin/**` | 登录 Token（Redis，有效期在「系统设置」页配，默认 20 小时） | `AdminAuthInterceptor` |

---

## 目录结构

```
sms-gateway/
├── android/          Android 客户端（Kotlin + Compose + Room + WorkManager）
│   └── app/src/main/java/com/smsgateway/app/
│       ├── receiver/     SmsReceiver 监听系统短信广播
│       ├── worker/       SmsUploadWorker 离线队列重试上报
│       ├── parser/       验证码提取与本地过滤
│       ├── qr/           扫码接入服务器（CameraX + ZXing）
│       ├── service/      前台服务，保活
│       └── ui/           Compose 界面
├── backend/          服务端（Spring Boot 3.2 + JPA + Redis）
│   ├── src/main/java/com/smsgateway/
│   │   ├── controller/   接口层
│   │   ├── interceptor/  三套鉴权拦截器
│   │   ├── service/      业务逻辑，CollectRuleEngine 是规则引擎
│   │   │   └── notify/   消息转发（v2）：调度器、渠道适配器、SSRF 防护、凭据加密
│   │   └── model/        entity / dto / enums
│   └── sql/schema.sql    建库 / 升级单文件（幂等，全新库与老库都适用）
├── management/       管理后台（Vue 3 + TypeScript + Element Plus）
│   └── src/views/        仪表盘 / 设备管理 / 短信记录 / 采集规则 / 转发渠道 /
│                         转发规则 / 投递记录 / API 密钥 / 接口文档 / 系统设置
├── docs/             专题文档（渠道对接指南等）
├── config/           本机开发配置覆盖（真身不入库，模板 application.yml.example 入库）
└── docker/           docker-compose.yml（MySQL + Redis + Backend + Frontend）
```

---

## 快速开始

### 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 18+ |
| Android Studio | 用于构建客户端（compileSdk 34，minSdk 26） |
| Docker | 可选：一条命令起全套（前后端 + MySQL + Redis，见方式一）。只在改代码、跑源码时可以不装 |

### 方式一：Docker —— 一条命令起全套

前后端 + MySQL + Redis 都在 `docker/docker-compose.yml` 里，**本机不需要装 JDK / Maven / Node**。

数据库和缓存有两种用法，**按需选一种**：

| | 起服务命令 | 库和缓存 |
| --- | --- | --- |
| **A. 用外部 MySQL / Redis**（默认） | `docker compose up -d --build` | 你自己的实例，地址填在 `.env` 里 |
| **B. 用自带的容器** | `docker compose --profile local-db up -d --build` | compose 里那两个容器 |

自带的两个容器挂在 `local-db` 这个 profile 上，**不带 `--profile` 就不会启动**。
选 A 要注意两件事：库得自己先建好（容器版才会自动灌 `schema.sql`），
以及 Redis 里明文存着管理员令牌和调用方 API Key —— 别让它裸奔在内网。

**1. 准备配置**

```bash
cd docker
cp .env.example .env
```

打开 `docker/.env` 填上真实值。下面几项是**必填的，没填就拒绝启动**（compose 用的是 `${VAR:?}`，不是 `:-默认值`——就是为了不给"悄悄用默认值"的机会）：

| 变量 | 说明 |
| --- | --- |
| `MYSQL_PASSWORD` | 数据库账号口令（两种模式都要：A 是连外部实例用的，B 是给容器初始化账号用的）|
| `REDIS_PASSWORD` | Redis 口令（里面明文存着管理员令牌与调用方 API Key）。**B 模式必填**（留空容器直接拒绝启动）；A 模式可留空 —— 外部 Redis 常常没设 `requirepass` —— 但那句话对你也成立，别把没口令的 Redis 放在内网里 |
| `APP_SECRET_KEY` | **系统主密钥**：设备令牌 = HMAC-SHA256(deviceId, 它)，泄漏等于可冒充任意设备。用 `openssl rand -hex 32` 生成 |
| `APP_ADMIN_DEFAULT_PASSWORD` | 首次建库时创建管理员账号用的口令 |
| `VITE_DEVICE_SERVER_URL` | 设备要访问的**后端**地址（会写进恢复码二维码）。**不能填 localhost** —— 对手机而言那是指向它自己 |

选 A 的话，还要填外部实例在哪（B 模式留空即用自带容器）：

| 变量 | 说明 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | 完整 JDBC 地址，`.env.example` 里有可照抄的一行 |
| `SPRING_DATASOURCE_USERNAME` | 数据库账号（B 模式下同时也用它建容器里的账号，默认 `smsuser`）|
| `SPRING_REDIS_HOST` / `SPRING_REDIS_PORT` | Redis 地址 |

`MYSQL_ROOT_PASSWORD` 只有 B 模式用得到（初始化自带容器的 root 账号）。

> A 模式跑之前，先把库建好（这条命令 B 模式用不上，容器会自动灌）：
> `mysql --default-character-set=utf8mb4 -u root -p sms_gateway < backend/sql/schema.sql`
> 库和表必须是 **utf8mb4** —— 容器那条路靠 MySQL 的 `--character-set-server=utf8mb4` 保证，
> 换成外部实例就没人替你保证了。

**2. 起服务**

```bash
# A. 用外部 MySQL / Redis（.env 里已填好外部地址）
docker compose up -d --build

# B. 用自带的 MySQL / Redis 容器
docker compose --profile local-db up -d --build
```

首次要构建前后端镜像（几分钟）。起来后：

| | 地址 | 端口变量（在 `docker/.env`）|
| --- | --- | --- |
| 管理后台 | `http://<主机>:8081` | `FRONTEND_PORT` |
| 后端 | `http://<主机>:8080` | `BACKEND_PORT` |
| MySQL（仅 B 模式）| `127.0.0.1:3306` | `MYSQL_PORT` |
| Redis（仅 B 模式）| `127.0.0.1:6379` | `REDIS_PORT` |

自带的 MySQL 与 Redis 只绑回环（`127.0.0.1`），不发布到网卡上。

初始账号 `admin` / `APP_ADMIN_DEFAULT_PASSWORD`。B 模式下首次启动会自动建库灌种子数据；
A 模式的库是你自己的，得先手工跑一次建表脚本（上面「准备配置」里那条）。

**本机已经跑着后端 / MySQL / Redis 时**有两种做法：把上面四个端口变量改成别的值让两套并存；
或者干脆走 A 模式，把 `.env` 里那几项指向你已有的实例（B 模式那两个容器就不再起了）。
端口改了 `docker compose up -d` 即可生效；但 **`VITE_DEVICE_SERVER_URL` 是构建期变量**
（Vite 在 build 时就把它内联进产物），改了必须 `docker compose build frontend`，
只重启容器不会生效。另外 `BACKEND_PORT` 改了就同步改它，否则手机会被指到另一个后端上去。

**3. 数据与清理**

这套用自己的数据卷，和你本机装的 MySQL 互不影响：

```bash
docker compose down        # 停掉，保留数据
docker compose down -v     # 连数据卷一起删（下次启动会重新建库并灌种子数据）
```

> **从 B 模式切到 A 模式时**：原来自带的那两个容器会变成 orphan —— 它们所在的服务已被
> profile 排除，compose 不再管它们，既不停也不删。收尾用
> `docker rm -f sms-gateway-mysql sms-gateway-redis`（数据都在 `mysql_data` 卷里，
> 删容器不丢数据），或者起服务时加 `--remove-orphans`。

**4. 前面还有反向代理时（重要）**

镜像里的 nginx 已配好 SPA 回退、`/api` 反代，以及 **SSE 必需的 `proxy_buffering off` 与
`proxy_read_timeout`**（见 `management/nginx.conf`）。**链路上任何一层反向代理都要加这两条** ——
最常见的漏法是容器前面再套一层 nginx 做 HTTPS 终止。界面的实时刷新走
`GET /api/admin/events`（Server-Sent Events），**不是轮询**；漏掉任一条的表现都是
「界面什么都不动、但也不报错」，最容易误判成前端坏了。配置以 `management/nginx.conf` 为准，
别在文档里另抄一份。

### 方式二：源码运行（改代码时用）

下面三步是日常开发的做法。只想把系统跑起来，用方式一就够了。

#### 1. 启动 MySQL 与 Redis

```bash
cd docker
docker compose --profile local-db up -d mysql redis
```

这两个服务挂在 `local-db` 这个 profile 上，所以带上 `--profile local-db`（不加也行 ——
compose 对命令行显式点到的服务不看 profile —— 但写出来意图更清楚）。
`docker-compose.yml` 会自动挂载 `backend/sql/schema.sql` 完成建库建表和
种子规则写入。若使用已有的 MySQL 实例，手工执行：

```bash
mysql --default-character-set=utf8mb4 -u root -p sms_gateway < backend/sql/schema.sql
```

`--default-character-set=utf8mb4` 不能省：中文 Windows 版 MySQL 的 client 字符集默认是
gbk，读 UTF-8 的脚本会报 `Data too long for column 'rule_name'` 这种看似毫不相干的错。

**已有库升级直接重跑 `schema.sql` 即可**，不必手工 ALTER：它本身就是幂等的 ——
第二、三段用存储过程查 `information_schema`，给已存在的库补列补索引、并在种子表为空时插入种子数据，
所以全新库、已有库、连跑几遍都行。

脚本内部已经写了 `SET NAMES utf8mb4`，因此**不依赖**上面那条客户端参数 ——
Docker 启动时用 `docker-entrypoint-initdb.d` 自动灌库那条路径（客户端不带参数、容器里没有 locale，
默认会退回 latin1）才不会把中文种子数据写成一堆乱码。

脚本是**幂等**的，且**不切库**（没有 `USE`）—— 所以命令里必须指定目标库，跑错库会直接报
`No database selected`，而不是静默写到别处去。全新库、老库升级、重复执行都用这一条命令。

#### 2. 启动后端

先把本机配置放好（**在仓库根执行**）：

```bash
cp config/application.yml.example config/application.yml
```

**数据库口令、转发加密密钥这类不该入库的值放这里**，模板见 `config/application.yml.example`。入库的是 `.example` 那份，真正的这份已在 `.gitignore` 里。三点要注意：它是**覆盖**不是替换（没写的项仍按 classpath 里的 `application.yml` 取值，所以模板只列了本机真正会改的几项，别把调优参数抄一份过去）；环境变量优先级仍高于它，docker / 生产那套不受影响；改它要重启。

然后启动：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.workingDirectory=..
```

服务监听 `http://localhost:8080`。

> **这个 `-D` 不能省。** Spring Boot 只加载**工作目录**下的 `config/application.yml`，而
> `mvn spring-boot:run` 的工作目录默认是 `${project.basedir}`，也就是 `backend/` ——
> 不加它，仓库根那份**不会生效**，而且**不报错**，只是悄悄用了 `application.yml` 里的默认值。
> 在 IDEA / Android Studio 里直接 Run 主类则不必管：它的工作目录默认是仓库根。

首次启动时，若 `admin_user` 表为空，`DataInitializer` 会按 `application.yml` 里的 `app.admin.default-username` / `default-password` 创建管理员账号（默认 `admin` / `DEV-ONLY-change-me`，**生产环境务必用环境变量覆盖**）。

#### 3. 启动管理后台

```bash
cd management
npm install
npm run dev
```

打开 `http://localhost:5173`，用上面的管理员账号登录。开发模式下 Vite 会把 `/api/` 代理到 `http://localhost:8080`。

生产构建：

```bash
npm run build     # 产物在 management/dist/
```

自己拿 `dist/` 挂到别的服务器上时，注意**方式一 · 4** 里那两条反向代理配置。

**这两个 `.env` 都已加入 `.gitignore`，只有 `.env.example` 入库**：
后端读 `docker/.env`（见 `docker/.env.example`），前端开发时读 `management/.env`
（见 `management/.env.example`，只有 `VITE_` 前缀的变量会进前端代码）。

### 4. 构建 Android 客户端

用 Android Studio 打开 `android/` 目录直接构建，或命令行：

```bash
cd android
gradle assembleDebug          # 注意：仓库未包含 gradle wrapper
```

> 仓库里没有 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`，命令行构建需要本机已装 Gradle，或先用 Android Studio 打开一次由它生成 wrapper。
>
> 正式签名走 `android/app/keystore.properties` + `release.jks`，这两个文件已加入 `.gitignore`，需自行提供；缺失时 release 构建会跳过签名配置，debug 构建不受影响。
>
> **源码包名与 APK 包名是分开的**：`namespace` 是 `com.smsgateway.app`（目录树里看到的那个），而 `applicationId` 是 `com.yunyi.smshub`。改 `applicationId` 等于换一个应用身份——系统会当成新装应用，本地存着的 `enrollSecret` 一并丢失，服务端也会多出一条设备记录。

### 5. 对接设备（快速连接）

1. 手机安装 App，授予短信、通知、电池优化白名单权限（MIUI 等 ROM 不加白名单会在息屏后杀掉前台服务，短信从此静默停报）。
2. 管理后台**顶栏**点**「快速连接」**（在任意页面都点得到），弹出的框里有服务器地址和一张二维码。
   同一处可以**生成接入口令**并启用准入校验（见下）。
3. 新手机打开 App → 点顶部**「扫一扫」**图标（主页状态会显示「未注册」，
   启动网关按钮下面也写着该点哪里）。**相机直接打开**，对准二维码即可。
4. 核对确认框里完整地址无误后确认，App 会依次 **保存地址 → 测试连通性 → 注册设备**；
   三步都有进度提示，失败会留在页面上说明原因（地址不对 / 对面不是本服务 / 口令被拒）。

   没相机、或相机权限被拒时，走右上角铅笔图标进「手动输入」：那里有 **服务器地址** 与
   **接入口令** 两个框（地址不必写 `http://`）。也可以把「复制连接信息」整段粘进地址框，
   会自动拆好 —— 两个值分别落在两个框里，能看清到底识别到了什么。
5. 设备注册成功后拿到 deviceToken，开始上报短信与心跳。

> **接入口令**：`/api/device/register` 必须免鉴权（设备得先能注册才拿得到令牌），
> 在那之前没有任何东西能区分「自己人」和「碰巧知道服务器地址的人」。生成一张口令后，
> 新设备必须扫带口令的二维码才能注册。它**只在首次注册时校验** —— 已注册设备的重新注册
> 走的是自己的设备密钥（`enrollSecret`），不受影响，所以开启口令不会把老设备关在门外。
>
> 口令**未生成或被停用时注册接口退回完全开放**（老部署升级后行为不变）。首次注册之外的
> 一切校验都不看它。
>
> 手机端「设置 → 配置二维码 → 导出到另一台设备」出的码同样会带上口令 —— 服务端启用
> 口令时，不带口令的码扫过去注册必然被拒，而那张码的用途正是让另一台设备接进来。
> 页面上有明确提示「拿到这张码的人都能把设备接入本服务器」。

### 6. 外部调用方取验证码

在管理后台「API 密钥」页签发一个 Key，然后：

```bash
# 阻塞等待验证码（最长 60 秒）
curl -H "Authorization: Bearer sk-xxxxxxxx" \
     "http://localhost:8080/api/sms/wait?phone=13800138000&timeout=60"
```

---

## 对外 API（API Key 鉴权）

请求头统一为 `Authorization: Bearer <api-key>`。响应体统一包装为：

```json
{ "code": 200, "message": "success", "data": { } }
```

密钥校验结果会在 Redis 缓存 60 秒；管理后台禁用或删除密钥时会主动失效缓存，无需等它过期。

### `GET /api/sms/wait` — 阻塞等待验证码

服务端挂起连接直到收到该号码的验证码或超时，调用方不必自己轮询。若等待期间已有验证码，立即返回。

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | string | 是 | — | 接收号码 |
| `timeout` | long | 否 | `60` | 超时秒数 |

**200 成功**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "smsCode": "483920",
    "sender": "10690329012345",
    "content": "【某某】您的验证码是 483920，5 分钟内有效。",
    "phone": "13800138000",
    "receiveTime": 1758096000000
  }
}
```

**408 超时**：`{"code": 408, "message": "wait timeout", "data": null}`

### `GET /api/sms/list` — 分页查询历史短信

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `phone` | string | 否 | — | 号码，服务端归一化后按 `LIKE %phone%` 匹配，容忍库中存量数据的 `+86` 前缀 |
| `startTime` | string | 否 | — | ISO-8601，如 `2026-09-17T15:29:21` |
| `endTime` | string | 否 | — | 同上 |
| `page` | int | 否 | `1` | 页码 |
| `pageSize` | int | 否 | `20` | 每页条数，上限 **100** |

**200 成功**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 128,
    "page": 1,
    "pageSize": 20,
    "records": [
      {
        "id": 1024,
        "phone": "13800138000",
        "sender": "10690329012345",
        "content": "【某某】您的验证码是 483920，5 分钟内有效。",
        "code": "483920",
        "receiveTime": "2026-09-17T15:29:21"
      }
    ]
  }
}
```

> 对外契约刻意不复用管理后台的视图对象：`deviceId` / `deviceName` / `status` / `isRead` 属内部信息，不出现在对外响应里。

---

## 设备 API（deviceToken 鉴权）

除注册外，均需 `Authorization: Bearer <deviceToken>`。

### `POST /api/device/register` — 设备注册（无需鉴权）

```json
{
  "deviceId": "a1b2c3d4e5f6",
  "deviceName": "备用机-1",
  "phoneNumber": "13800138000",
  "platform": "android",
  "appVersion": "1.0.0",
  "enrollSecret": "…",
  "enrollToken": "…"
}
```

`phoneNumber` 兼容客户端发来的 `phone` 字段（`@JsonAlias`）。返回 `deviceToken`、`deviceId`、`status`。手机号允许为空——多数 Android 设备读不到本机号码，这不应阻断注册。

两个 `enroll*` 字段名字像，语义完全不同，**不要混**：

| 字段 | 证明什么 | 谁生成 | 什么时候校验 |
| --- | --- | --- | --- |
| `enrollSecret` | 「我是**这台设备**」 | 设备自己（服务端只存 SHA-256） | deviceId **已存在**时 |
| `enrollToken` | 「我被允许接入**本服务器**」 | 管理员在控制台生成 | deviceId **不存在**时（首次注册） |

两者任一不通过都返回 **403** 而不是 400：设备端把 400 归为终态直接放弃，只有 403 才带得出
「去控制台签恢复码」/「去管理后台重新扫码」这类可执行的提示。

### `POST /api/device/heartbeat` — 心跳

上报 `deviceId`、`deviceName`、`phoneNumber` 以及遥测字段 `battery`、`network`、`charging`、`pendingCount`。响应体带回设备 `status`，设备据此得知自己是否被管理员禁用。

> 被禁用的设备**仍可发心跳**。这是刻意的：心跳是设备唯一能发现自己被恢复的通道，也让管理端能持续区分「禁用但设备还活着」和「禁用且失联」。

### `POST /api/device/offline` — 主动下线

无请求体，身份取自令牌。设备关闭网关时调用，让控制台立刻显示离线而不是等心跳超时。**尽力而为**：进程被系统杀掉时发不出这个请求，那种情况仍由心跳超时兜底。

### `GET /api/device/sms` — 本设备的短信记录

参数 `page`（默认 1）、`pageSize`（默认 20）、`includeIgnored`（默认 `true`）。设备身份取自令牌，由拦截器写入请求属性，**不接受 `deviceId` 参数**——否则任何设备都能查到别人的记录。

### `GET /api/device/sms/stats` — 本设备今日统计

返回 `{ "todaySms": 12, "todayCodes": 8 }`。设备端读这里而非本地库，避免因清理历史或清除应用数据导致「数字是 0、点进去却有内容」。

### `POST /api/sms/receive` — 上报短信（deviceToken 鉴权）

可选请求头 `Idempotency-Key`。

```json
{
  "deviceId": "a1b2c3d4e5f6",
  "localMessageId": "msg-1758096000000-1",
  "phone": "13800138000",
  "sender": "10690329012345",
  "content": "【某某】您的验证码是 483920，5 分钟内有效。",
  "code": "483920",
  "receiveTime": 1758096000000
}
```

```json
{ "code": 200, "message": "success",
  "data": { "messageId": 1024, "duplicate": false, "status": "RECEIVED", "smsCode": "483920" } }
```

去重是双保险：`(device_id, local_message_id)` 唯一键 + `source_hash`（正文 SHA-256）唯一键。重复上报返回 `message: "duplicate"`，`duplicate: true`。

> ⚠️ 重复到达**不会新插一行**：撞上 `source_hash` 唯一键之后就地在原行累加 `duplicate_count`
> （`updated_at` 顺带被顶到最后一次），所以库里 `sms_message.status` 只可能是
> `RECEIVED` / `IGNORED` —— **没有 `DUPLICATE` 这一档**。
> 「这条内容又来了几次」看 `duplicate_count`：管理端短信列表的「判定」列与展开行
> 显示的就是它。照 `status` 去判「是不是重复」会一直判不出来。

---

## 管理后台 API

前缀 `/api/admin`，除 `POST /auth/login` 外均需登录 Token。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/login` | 登录，返回 Token |
| POST | `/auth/logout` | 登出，失效 Token |
| GET | `/events` | **SSE 事件流**，界面实时刷新的唯一来源（不是轮询）。返回流不是 `ApiResult` |
| GET | `/device/list` | 设备列表 |
| GET | `/device/stats` | 设备统计 |
| GET | `/device/{deviceId}` | 设备详情 |
| PUT | `/device/{deviceId}/enabled` | 启用 / 禁用设备 |
| POST | `/device/{deviceId}/recovery-code` | 签发恢复码（**只此一次**回明文 `enrollSecret`） |
| DELETE | `/device/{deviceId}` | 删除设备，连同其短信一起删（返回删掉的条数） |
| GET | `/sms/list` | 短信列表 |
| GET | `/sms/device/{deviceId}` | 指定设备的短信 |
| GET | `/sms/stats/daily` | 按日统计 |
| GET | `/rule/list` | 规则列表 |
| POST | `/rule` | 新建规则 |
| PUT | `/rule/{id}` | 修改规则 |
| DELETE | `/rule/{id}` | 删除规则 |
| PUT | `/rule/{id}/enabled` | 启用 / 停用规则 |
| GET | `/apikey/list` | 密钥列表 |
| POST | `/apikey` | 签发密钥 |
| DELETE | `/apikey/{id}` | 删除密钥 |
| PUT | `/apikey/{id}/enabled` | 启用 / 停用密钥 |
| GET | `/enroll-token` | 当前设备接入口令（明文；`token` 为 null 表示未生成） |
| POST | `/enroll-token/rotate` | 生成 / 轮换接入口令，并启用准入校验 |
| PUT | `/enroll-token/enabled` | 启用 / 停用准入校验（停用不删除口令） |
| GET | `/notify/status` | 转发是否就绪（`ready`，即加密密钥在不在） |
| GET | `/notify/channel/types` | 支持的转发渠道类型（前端选择器用） |
| GET | `/notify/channel/list` | 转发渠道列表（**凭据打码**） |
| POST | `/notify/channel` | 新建渠道 |
| PUT | `/notify/channel/{id}` | 修改渠道（`config` 留空 = 不修改） |
| DELETE | `/notify/channel/{id}` | 删除渠道（投递记录保留） |
| PUT | `/notify/channel/{id}/enabled` | 启停渠道 |
| POST | `/notify/channel/{id}/test` | 发送测试消息（不含真实验证码） |
| GET | `/notify/route/list` | 转发规则列表 |
| POST | `/notify/route` | 新建规则（必须至少配一个渠道） |
| PUT | `/notify/route/{id}` | 修改规则 |
| DELETE | `/notify/route/{id}` | 删除规则 |
| PUT | `/notify/route/{id}/enabled` | 启停规则 |
| GET | `/notify/delivery/list` | 投递记录（可按渠道/状态过滤） |
| POST | `/notify/delivery/{id}/retry` | 手动重投 |
| GET | `/sysconfig/list` | 全部运行期配置项（带 label / 说明 / 分组，前端直接渲染） |
| PUT | `/sysconfig` | 改一项配置，**立即生效、不用重启**。只认 `SysConfigKey` 里列出的键 |

管理后台左侧「接口文档」页内也有一份可交互的接口说明。

---

## 短信采集规则

规则决定一条短信是采集还是忽略，由 `CollectRuleEngine` 执行：

> 一条规则命中 = **发送方命中** 且（**关键词为空** 或 **关键词命中正文**）
> 按 `priority` 降序逐条判定，首条命中即定论；全部不命中则**默认采集**（保底不丢验证码）。

`match_type` 对发送方和关键词共用，取值 `EXACT` / `LIKE` / `REGEX`。

> ⚠️ 「任意发送方」在 `LIKE` 下写 `%`，在 `REGEX` 下必须写 `.*` —— 正则里 `%` 是普通字符，写成 `'%'` 永远匹配不上。

建库脚本内置三条种子规则：

| 规则名 | 发送方 | 关键词 | 匹配 | 动作 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 验证码短信 | `%` | `验证码` | LIKE | collect | 100 |
| 国际验证码 | `.*` | `code\|verification\|verify` | REGEX | collect | 100 |
| 营销类忽略 | `.*` | `营销\|广告\|退订` | REGEX | ignore | 10 |

---

## 配置说明

### 后端 `backend/src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/sms_gateway?...
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:root}
  data:
    redis:
      host: ${SPRING_REDIS_HOST:localhost}
      port: ${SPRING_REDIS_PORT:6379}
      password: ${SPRING_REDIS_PASSWORD:}

app:
  secret:
    key: ${APP_SECRET_KEY:DEV-ONLY-...}      # 设备令牌的 HMAC 主密钥；仍是默认值时启动会告警
  admin:
    default-username: ${APP_ADMIN_DEFAULT_USERNAME:admin}
    default-password: ${APP_ADMIN_DEFAULT_PASSWORD:DEV-ONLY-change-me}
    reset-password: ${APP_ADMIN_RESET_PASSWORD:}  # 唯一能改已存在账号口令的途径，用完撤掉
  sms:
    cleanup-batch-size: 1000             # 清理每批删多少行（保留天数与执行时间在库里）
  notify:
    encrypt-key: ${NOTIFY_ENCRYPT_KEY:}  # 渠道凭据的加密密钥；留空则转发不可启用
    dispatcher:
      batch-size: 100
      max-concurrent-channels: 8         # 渠道之间并发，**同一渠道内部串行**
      retry-base-ms: 5000                # 退避基数，实际等待是 Full Jitter
      retry-cap-ms: 3600000
    stuck-sending-seconds: 120           # 卡在 SENDING 超这么久就退回待投递
    http:
      connect-timeout-ms: 3000
      read-timeout-ms: 10000
      max-response-bytes: 8192           # 对端是管理员填的任意地址，响应体要设上限
```

所有配置项均可通过环境变量覆盖（`SPRING_DATASOURCE_URL`、`SPRING_REDIS_HOST` 等），`docker/docker-compose.yml` 里即为示例。

> **运行时能调的配置不在这个文件里。** 转发总开关、附带来源信息、轮询间隔、失败阈值、
> 短信保留天数、清理时间、登录有效期、API Key 校验缓存 —— 这八项在数据库 `sys_config` 表，
> 管理后台「系统设置」页改完**立即生效**。判断标准只有一条：**密钥留环境变量，
> 运行期策略进数据库**（见 `SysConfigKey`，那里是唯一的真源，别再往文档里抄一份）。

### 管理后台 `management/vite.config.ts`

开发服务器端口 `5173`，`/api/` 代理到 `http://localhost:8080`。

---

## 消息转发（v2）

短信到达后由**服务端**主动推送到群机器人 / 个人 IM（企微群、飞书、钉钉、
Telegram、Slack、以及推到个人微信的 WxPusher / Server酱 / PushPlus），
不必有人盯着后台。

### 为什么在服务端而不是手机端

手机端已经有完整的短信上报链路，在它上面加一个 HTTP 转发看起来省事得多。但代价不对等：

1. **没有事务可依附。** 转发任务是在接收短信的**同一个事务**里写入的（事务性发件箱），
   事务提交则投递任务必然存在，回滚则两者都不存在。放手机上就没有这个保证 ——
   上报成功、转发失败时服务端根本看不见，后台显示一切正常而群里没收到。
2. **凭据要分发到每台手机。** webhook 地址就是凭证，轮换一个要重配所有手机，
   而手机在现场。放在服务端则加密入库（AES-GCM），管理端只回显打码值。
3. **限流按「群」算，不按「手机」算。** 5 台手机各自按自己的节奏发，合起来必然超限，
   而没有任何一处知道。投递记录也会散成 N 份且不可靠。
4. **手机端本来就不承载策略。** 见 `parser/SmsFilter.kt`：连一个可配置的过滤开关都
   刻意没做，理由是「网关的职责就是只捞验证码短信」。转发规则比它复杂得多。

### 配置步骤

```bash
# 1. 生成渠道凭据的加密密钥（丢了的话所有渠道凭据都要重配）
openssl rand -base64 32

# 2. 写进 docker/.env
NOTIFY_ENCRYPT_KEY=<上一步的输出>

# 3. 灌入新表（幂等，只新增表、不动任何现有表）
#    老库跑这一遍会补上缺的：device_enroll_token / notify_channel / notify_route /
#    notify_route_channel / notify_delivery / sys_config
mysql -u root -p sms_gateway < backend/sql/schema.sql
```

然后在管理后台：

```
系统设置  →  打开「消息转发」开关        ← 改完立即生效，不用重启
转发渠道  →  建一个渠道 → 点「测试」确认能收到
转发规则  →  把渠道和短信条件关联起来    ← 缺这步不会有任何短信被转发
```

**转发总开关在「系统设置」页，不在环境变量里。** 原先它是 `APP_NOTIFY_ENABLED`，
改一次要重启一次容器 —— 现在改完立即生效。同在那里的还有：

| 分组 | 配置项 |
| --- | --- |
| 消息转发 | 总开关、是否附带来源信息、轮询间隔、渠道失败阈值 |
| 短信数据 | 保留天数、清理执行时间 |
| 安全与会话 | 管理后台登录有效期、API Key 校验缓存时长 |

判断一项配置该放哪边的标准只有一条：**密钥留环境变量，运行期策略进数据库**。
加密密钥尤其不能进库 —— 用库里的密钥去解库里的密文是循环依赖。

> 后端没配 `NOTIFY_ENCRYPT_KEY` 时**照常启动**，只是转发不可启用 ——
> 一个可选能力不该成为整个应用的启动依赖。你在界面上打开开关的那一刻会收到
> 一条说明该设什么变量的提示。

> 每个渠道**去哪儿拿凭据、填进哪个字段、有哪些坑**，见
> [`docs/notify-channel-setup.md`](docs/notify-channel-setup.md)。

### 转发出去的是什么

**就是短信原文**，前面拼一行元信息（设备 · 发送方 · 时间），不做模板、不给验证码打码。

```
【备用机-1 · 10690300 · 09-20 10:30:00】
【某某】您的验证码是 483920，5 分钟内有效。
```

> ⚠️ **这意味着群里所有人都能看到并能使用每一个验证码** —— 等于把对应账号的登录权限
> 给了他们（谁手快谁用），而且运行时不报错、没有任何提示信号。请确认那个群里只有你自己。
> 管理端新建渠道的弹窗里有这条提示，这是配置时就该知道的事。

元信息那一行不能省：多台手机或双卡时，只看到一段正文根本分不清是哪台机器、哪个号收到的。
设备名读不到时会自动省掉那一段，不会留下孤零零的分隔符。

不做模板是刻意的：可配置模板是一整块配置面（占位符、默认值、前端表单、渲染异常），
而个人短信网关没有「同一条短信按不同渠道显示成不同样子」的需求。写死的格式够用，
而写死的东西不会配错。

### 支持的渠道

| 类型 | 推到哪 | 官方限流 | 本实现的默认值 |
| --- | --- | --- | --- |
| 企业微信群机器人 | 群 | 20/分钟 | 20 |
| 飞书群机器人 | 群 | 100/分钟、5/秒 | 60 |
| 钉钉群机器人 | 群 | 20/分钟（超限罚 10 分钟） | 15 |
| Telegram Bot | 个人 / 群 | 群 20/分钟、单 chat 1/秒 | 20 |
| Slack Webhook | 频道 | 1/秒（超限可能永久禁用） | 15 |
| WxPusher | 个人微信 | 约 2 QPS，无每日上限 | 60 |
| Server酱 | 个人微信 | 50/分钟，**免费仅 5 条/天** | 20 |
| PushPlus | 个人微信 | 1 分钟 5 次 / 200 次/天 | 5 |
| 通用 Webhook | 任意（含内网） | —— | 60 |
| 企业微信应用消息 | 企业微信个人 | 单成员 30/分钟 | 30 |

加一种渠道 = 在 `NotifyChannelType` 加一个枚举值 + 写一个 `ChannelSender` 实现。
表结构、调度器都不用动；管理端下拉是从 `/channel/types` 取的，也没有硬编码。
`ChannelSenderRegistry` 启动时会校验「枚举里每个值都有实现」，少一个直接拒绝启动 ——
所以不会出现「能选、能存库、但发不出去」的那种坑。

### 能做与不能做

**调研时间 2026-09。** 平台政策会变，下面每条都附了依据，便于日后重新评估。
支持的渠道见上面那张表；下面是**没做**的那些，以及为什么。

#### ❌ QQ（任何形式）

**官方主动推送能力已被关闭。** QQ 机器人官方公告《关于 QQ 机器人消息推送策略调整通知》
（2025-04-16 发布）原文：

> 由于业务运营策略调整，预计 4 月起 QQ 机器人将不再支持「主动消息推送功能」，
> 平台将陆续进行能力收敛。

官方文档源码中至今保留红色警告：**「主动推送能力于 2025 年 4 月 21 日起不再提供，
接口调用时会收到错误信息。」**

这对本项目是**根本性**的打击 —— 短信转发的本质就是「由外部事件触发、无用户前置交互的
主动推送」，恰好是被砍掉的那个能力。

> **口径目前自相矛盾**：官方渲染版文档（2026-07-21 更新）里又重新列出了主动消息频控表
> （群聊 60 qpm / 单关系 20 qpm / 每日 1000 条）。据社区消息（非官方），2025 年 6 月起
> 腾讯以「残缺版」恢复：群聊场景默认关闭、需群主手动开开关、且用户可单方面关闭接收。
> 即便按最乐观的口径，也要受「群主手动开开关 + 20 qpm + 用户可拒绝」三重约束，
> 而官方原文明确「如果设置了关闭，主动消息一律发送失败」。**把高可靠送达建立在这种
> 能力上，工程上不可接受。**

**社区途径有明确的合规与封号风险：**

- 腾讯《QQ 软件许可及服务协议》第 8.2.2 条明文禁止：「形式包括但不限于使用插件、
  外挂或非腾讯经授权的第三方工具/服务接入本软件和相关系统」，且逐字列出了
  「机器人、自动化程序、脚本」
- 技术上这些方案（NapCat / LLBot 走 NTQQ 客户端 Hook，Lagrange 走协议逆向）
  在服务端视角就是「伪装成正常客户端」，被检测到会判定为风险账号
- 实际封禁形态：风险警告 → 踢下线 → 限制社交功能 → 封 7 天 → **永久冻结且申诉失败**。
  被封的是**用户自己的主 QQ 号**
- 生态本身也在萎缩：**go-cqhttp 已停止维护**（作者原话「协议库的时代已经过去」，
  代码冻结于 2024-05）；LiteLoaderQQNT 已归档；Shamrock 仓库已 404。目前只有 NapCat 活跃

> ⚠️ 协议原文来自搜索转录 —— 腾讯规则页 `rule.tencent.com` 有反爬，当时未能直连抓取。
> 这只影响上面论证的强度，不影响「不做 QQ」这个结论（官方主动推送下线有公告可查）。
> 若要重新评估，建议先人工核对 `rule.tencent.com` 的原页面。

**如果你确实需要腾讯系推送，用企业微信群机器人** —— 它是真正的「一个 URL 即可推送」，
合规且稳定。已实现。

#### ❌ 个人微信群（家庭群、朋友群）

官方无任何 API。任何声称能做到的方案都走的是非官方协议，同上述风险。

#### ❌ 公众号模板消息

**先澄清一个误解：模板消息并没有下线。** 官方文档顶部原文写着「模板消息能力可正常使用」，
接口文档更新于 2025-11-26，无任何下线公告。网上的「已下线」说法源于 2023 年的一次改版
（历史模板库停止新增，改走类目模板库）被内容农场误读。

**但对本项目它是错的工具。** 官方运营规范明文规定：

> 模板消息的定位是**用户触发后的通知消息，不允许在用户没做任何操作或未经用户同意
> 接收的前提下，主动下发消息给用户**。
> 某用户仅仅是关注服务号，没有和服务号及其所属主体有任何交互行为，却无故收到该服务号
> 下发的模板消息，**属于违规行为**。

短信转发正是「无用户触发的主动下发」，语义直接冲突，违规后果是阶梯性接口封禁直至封号。
此外还有：仅认证服务号可用、必须用户已关注、服务端 IP 白名单强制（出口 IP 浮动会直接
`40164`）。

#### ✅ 「转发到我的微信」的可行路径

个人微信没有官方机器人 API。能把消息送到你个人微信的只有第三方推送服务 ——
它们本质是「注册一个公众号/应用，用模板消息推给关注了的你」，把合规问题留给了自己。
本项目实现了三个：

| 服务 | 免费额度 | 备注 |
| --- | --- | --- |
| **WxPusher** | **无每日条数上限**（限 ~2 QPS） | **首选**。官方文档明确点名「短信转发系统」是其 SPT 模式的典型场景 |
| PushPlus | 未实名 0 条；实名后 200 次/天 | 接口是**异步**的 |
| Server酱 | **仅 5 条/天** | 够测试，不够日常用 |

配置方法见 [`docs/notify-channel-setup.md`](docs/notify-channel-setup.md)。

### 几个实现上的坑（都已处理，列出来便于排查）

- **飞书与钉钉的加签规则四处全反着**：HMAC 的 key 是什么、待签数据是什么、
  时间戳用秒还是毫秒、参数放 body 还是 URL query。见 `BotSignatures` 与它的单测。
- **企微群机器人的 HTTP 状态码永远是 200**，成败在响应体的 `errcode` 里。
- **WxPusher 的成功判定是 `code == 1000`**，不是 200。
- **PushPlus 的接口是异步的**：返回 200 只代表请求被受理，不代表已发出。
  本实现判定到此为止，「成功」的含义比别的渠道弱一层。
- **企微应用消息的 `enable_duplicate_check` 必须显式设 0**：它默认 1800 秒内相同内容
  不重复下发，而重试发的正是相同内容 —— 不关掉的话重试会被静默吞掉，
  投递记录写着成功、用户从没收到。
- **Telegram 用 HTML 模式**，不用 MarkdownV2（后者要转义 18 个字符，短信正文里全是）。

---

## 设备端排查

手机侧的问题看起来都像「App 坏了」，但多数是环境或系统行为。下面三条都在真机
（Redmi K60 Ultra / Android 14 / HyperOS）上量过，现场先照这个顺序看。

### 刚连上 WiFi 就扫码连接，总是「连不上」或者等很久

**现象**：内网部署，扫「快速连接」二维码时快时慢、像看运气；过一会再扫一次就好了。

**原因**：WiFi 连上、`ip addr` 里已经有地址之后，**还要十几秒局域网才真正通**。
这期间的连接要么被秒回「本机没路由」，要么干等超时：

```
11:22:31  2039ms FAIL ip=1   ← IP 到手了，但仍不通
11:22:43  2044ms FAIL ip=1
11:22:46  1063ms OK   ip=1   ← 首次通，慢
11:22:48    78ms OK   ip=1   ← 之后一直正常 50-90ms
```

而扫码连接恰好就发生在这十几秒里。窗口长度还不稳定：刚关联时约 15 秒，
反复关开 WiFi 之后量到过 28 秒以上仍不通 —— 像是 AP 的客户端表/漫游没收敛，
换 AP 或等 AP 那边收敛就没了。

**App 这边做了什么**（`NetworkWatch` + `RetrofitClient.probe`）：WiFi 连上 30 秒内把探测的
重试放宽到 8 次 / 25 秒预算，且慢失败也重试；窗口外保持原来的快节奏（服务器真的不可达时
不该从等 8 秒变成等 20 秒）。失败文案会直接说「刚连上 WiFi 时局域网一般要十几秒才通，
稍等再试一次」，结果页给的「重试」也是用**已保存的地址**重跑，不必重新扫码。

**现场怎么办**：连上 WiFi 后等十几秒再扫；已经失败了就点「重试」。

**如果不是这个原因**，按失败文案往下查：「连接被拒绝」＝对面在但端口上没有服务；
「连接超时」＝包发出去了对面没回（查服务端防火墙）；「有响应但不是本服务」＝地址或端口填错了；
「连接被本机中断」＝本机这一侧断的，看是不是网络刚切换。

### 关掉 App 之后还能收短信吗

**能。** 只有在系统设置里「强制停止」（或 `am force-stop`）才会真的收不到。

- 点返回、从最近任务上滑清掉：**前台服务把进程顶住了**，服务继续跑、心跳继续发。
  真机上验过：清掉任务回到桌面之后 `isForeground=true`、运行中的通知还挂着。
- 收短信本身走清单里声明的广播接收器，系统会把短信投给应用，进程不在也会为它拉起来。
- 上报走 WorkManager，它的开关是**持久化**的 `gateway_running`，跟界面在不在没关系。
- 重启、覆盖安装之后由 `BootReceiver`（`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`）重新拉起服务。

三点例外：「强制停止」会拦掉所有广播直到手动再开一次 App；系统把服务杀掉（没加电池
白名单时会，见主页那条橙色横幅）会让心跳断、后台显示离线，但**短信照收照传**，
下次打开 App 会自动把服务拉回来；界面上主动停掉网关时，短信只入库、按产品承诺不上报。

### 读不到手机号是常态，不是故障

号码存在 SIM 卡里，而多数现代运营商根本不往里写。App 依次试三条路
（`SubscriptionManager.getPhoneNumber` → `SubscriptionInfo.getNumber` →
`TelephonyManager.getLine1Number`），都读不到时设置页会说明**是哪一种读不到**并提示手填 ——
手填的值和自动读到的等效。双卡机上只会采用第一张读得出号码的卡。

> 注意区分另外两种「读不到」：没授予电话权限（设置页会给申请入口）、系统不返回卡列表
> （`SubscriptionManager` 在某些 ROM 上被限制）。这三种以前在界面上长得一样，
> 现在文案是分开的。

---

## 安全注意事项

**部署前请务必处理以下几项：**

1. **修改默认管理员口令。** `application.yml` 里的 `app.admin.default-password: DEV-ONLY-change-me` 是明文默认值，生产环境必须通过环境变量覆盖，且只在 `admin_user` 表为空时生效——建库后改配置不会更新已存在的账号。要轮换一个**已存在**账号的口令，设 `APP_ADMIN_RESET_PASSWORD` 重启一次，确认能登录后把它撤掉（这是目前唯一能改口令的途径，管理接口里没有这个功能）。

2. **更换 `app.secret.key`（环境变量 `APP_SECRET_KEY`）。** 该值用于设备 Token 的 HMAC，且是**确定性推导**——知道它的人可以对任意 deviceId 直接算出合法令牌，冒充任意设备。更换会让所有已签发令牌立即失效，趁设备还少的时候把它定下来。

3. **API Key 在库中是明文存储的。** 管理后台列表默认打码，但支持「点击显示完整值」，因此无法只存哈希。库被读走等同于密钥全部泄漏——这是内部系统的取舍，请相应收紧数据库访问权限。

4. **Android 客户端允许明文 HTTP。** `network_security_config.xml` 允许访问任意主机，以支持内网 IP 部署；代价是一张二维码即构成未认证的配置注入通道——恶意二维码可把设备指向攻击者的服务器，此后每条短信和验证码都会被截走。因此客户端解码二维码后**不自动保存**，必须由用户核对完整地址并确认（「快速连接」的一键流程同样保留这一步）。

8. **设备接入口令是明文存储的**（同第 3 条，管理后台要把它显示进二维码，无法只存哈希），且**拿到二维码的人就能把设备接进本服务器**。它的作用是拦住「只知道服务器地址的人」，不是替代认证——需要更强的准入控制时，应当把管理后台本身放到受控网络里。口令可在控制台一键轮换，旧码立即失效；已注册设备不受轮换影响。

9. **接入口令未生成或被停用时，`/api/device/register` 对任何人开放。** 这是刻意的向后兼容（老部署升级后新设备不会突然接不进来），但意味着**默认状态是没有准入控制的**。需要时请在控制台「快速连接」里显式生成口令。

10. **转发到群会改变验证码的安全边界。** 验证码的安全性建立在「只有持有手机的人能看到」之上；而转发出去的是**短信原文，不做任何处理** —— 群里所有人都能看到并能使用它。不只是「别人也看到了」，而是**你的验证码等于作废**，更严重的是未授权的人可以借此登录你的账号。**只在群里只有你自己的时候启用转发。** 管理端建渠道时会提示一次，但运行时不会拦。

13. **「系统设置」页的配置改完立即生效，且只存在数据库里。** 那不是密钥（密钥仍在环境变量里）—— 但要知道：谁拿到管理员账号，谁就能打开转发开关、把短信推到他自己的渠道上。所以管理员账号的强度与「系统设置」的权限边界是一回事，别只把它当成一个「方便改配置」的页面。

11. **通用 Webhook 的 SSRF 防护不能关。** 它是唯一允许管理员填任意 URL 的渠道，**管理后台账号一旦被盗，这个功能就是一个现成的内网探测器**（打 `169.254.169.254` 可取云厂商临时凭证）。默认拦截回环、RFC1918 私网、CGNAT、IPv6 唯一本地地址与元数据服务，并且**解析全部 A/AAAA 记录逐个校验**（防 DNS rebinding）、**不跟随重定向**。对接内网系统时的 `allow_private_network` 开关默认关，打开会记审计日志。

12. **渠道凭据是加密存储的**（AES-GCM，密钥走 `NOTIFY_ENCRYPT_KEY` 环境变量），这与 `api_key` 的明文存储刻意相反 —— webhook 地址没有回显明文的场景，能做加密就做。管理端、日志、`last_error` 里的 URL 都会被打码（查询参数与长路径段），这个密钥丢了则所有渠道凭据都要重配。

5. **不要提交签名材料。** `android/app/release.jks` 与 `android/app/keystore.properties` 已加入 `.gitignore`。签名私钥入库意味着他人可签出能覆盖安装的包，请确保它们从未被 `git add`。

6. **CORS 当前为 `allowedOrigins("*")`**（`WebConfig`），生产环境建议收敛为管理后台的实际域名。

7. **建议全站 HTTPS。** 短信正文与验证码在明文 HTTP 下可被中间人读取。

---

## 开发与测试

```bash
# 后端测试（规则引擎、上报服务、转发调度与签名、SSRF 防护、凭据加解密）
cd backend && mvn test

# 管理后台类型检查 + 构建
cd management && npm run build

# Android 单元测试（二维码编解码）
cd android && gradle test
```

### 一些实现上的取舍

- **Android release 不开混淆。** Gson 靠反射读字段名、Room 同理，被 R8 改名后会静默失败（表现为「接口通了但字段全是 null」）。要开启需先补全 keep 规则并回归测试。
- **Android 离线队列。** 短信先写入 Room，再由 WorkManager 重试上报，弱网或息屏时不丢数据。
- **前台服务 + 电池优化白名单。** MIUI 等 ROM 会在息屏后杀掉前台服务且无任何提示，因此引导用户加白名单，部分 ROM 拒绝该 Intent 时退回应用详情页。
- **数据库升级就是重跑一遍 `sql/schema.sql`。** 它是幂等的单文件：建表用 `IF NOT EXISTS`，补列补索引先查 `information_schema` 再动手（MySQL 8.0 没有 `ADD COLUMN IF NOT EXISTS`，只能这么写），种子数据只在表为空时插入。全新库与老库升级共用这一条命令。

---

## License

未指定。
