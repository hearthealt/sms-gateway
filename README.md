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
- [配置说明](#配置说明)
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
| 管理后台 | `/api/admin/**` | 登录 Token（Redis，默认 2 小时） | `AdminAuthInterceptor` |

---

## 目录结构

```
sms-gateway/
├── android/          Android 客户端（Kotlin + Compose + Room + WorkManager）
│   └── app/src/main/java/com/smsgateway/app/
│       ├── receiver/     SmsReceiver 监听系统短信广播
│       ├── worker/       SmsUploadWorker 离线队列重试上报
│       ├── parser/       验证码提取与本地过滤
│       ├── qr/           扫码导入服务器地址（CameraX + ZXing）
│       └── service/      前台服务，保活
├── backend/          服务端（Spring Boot 3.2 + JPA + Redis）
│   ├── src/main/java/com/smsgateway/
│   │   ├── controller/   接口层
│   │   ├── interceptor/  三套鉴权拦截器
│   │   ├── service/      业务逻辑，CollectRuleEngine 是规则引擎
│   │   └── model/        entity / dto / enums
│   └── sql/              schema.sql（建库）+ migrations/（已建库的增量升级）
├── management/       管理后台（Vue 3 + TypeScript + Element Plus）
│   └── src/views/        仪表盘 / 设备 / 短信 / 规则 / API 密钥 / 接口文档
└── docker/           docker-compose.yml（MySQL + Redis + Backend）
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
| Docker | 可选，用于一键起 MySQL / Redis |

### 1. 启动 MySQL 与 Redis

```bash
cd docker
docker compose up -d mysql redis
```

`docker-compose.yml` 会自动挂载 `backend/sql/schema.sql` 完成建库建表和种子规则写入。若使用已有的 MySQL 实例，手工执行：

```bash
mysql --default-character-set=utf8mb4 -u root -p sms_gateway < backend/sql/schema.sql
```

`--default-character-set=utf8mb4` 不能省：中文 Windows 版 MySQL 的 client 字符集默认是
gbk，读 UTF-8 的脚本会报 `Data too long for column 'rule_name'` 这种看似毫不相干的错。

**已有库升级不要重跑 `schema.sql`。** 它通篇是 `CREATE TABLE IF NOT EXISTS`，
对已存在的表**什么都不做** —— 新加的列、换掉的索引都不会生效，而后端已经在用它们了，
启动后第一次查询就会报 `Unknown column`。升级走 `backend/sql/migrations/` 下对应日期的脚本：
那些是增量的、**非幂等**（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`），逐条确认后执行。

脚本是**幂等**的，且**不切库**（没有 `USE`）—— 所以命令里必须指定目标库，跑错库会直接报
`No database selected`，而不是静默写到别处去。全新库、老库升级、重复执行都用这一条命令。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

服务监听 `http://localhost:8080`。

也可以用 Docker 连后端一起起（需先 `mvn package` 生成 jar，Dockerfile 依赖 `target/*.jar`）：

```bash
cd backend && mvn clean package -DskipTests
cd ../docker && docker compose up -d
```

首次启动时，若 `admin_user` 表为空，`DataInitializer` 会按 `application.yml` 里的 `app.admin.default-username` / `default-password` 创建管理员账号（默认 `admin` / `zaq1,lp-`，**生产环境务必用环境变量覆盖**）。

### 3. 启动管理后台

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

### 4. 构建 Android 客户端

用 Android Studio 打开 `android/` 目录直接构建，或命令行：

```bash
cd android
gradle assembleDebug          # 注意：仓库未包含 gradle wrapper
```

> 仓库里没有 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`，命令行构建需要本机已装 Gradle，或先用 Android Studio 打开一次由它生成 wrapper。
>
> 正式签名走 `android/app/keystore.properties` + `release.jks`，这两个文件已加入 `.gitignore`，需自行提供；缺失时 release 构建会跳过签名配置，debug 构建不受影响。

### 5. 对接设备

1. 手机安装 App，授予短信、通知、电池优化白名单权限（MIUI 等 ROM 不加白名单会在息屏后杀掉前台服务，短信从此静默停报）。
2. 在管理后台「设备管理」页为目标地址生成配置二维码。
3. App 内扫码导入服务器地址（**解码后不会自动保存，需人工确认完整地址**，见「安全注意事项」）。
4. 设备注册成功后拿到 deviceToken，开始上报短信与心跳。

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
  "appVersion": "1.0.0"
}
```

`phoneNumber` 兼容客户端发来的 `phone` 字段（`@JsonAlias`）。返回 `deviceToken`、`deviceId`、`status`。手机号允许为空——多数 Android 设备读不到本机号码，这不应阻断注册。

### `POST /api/device/heartbeat` — 心跳

上报 `deviceId`、`deviceName`、`phoneNumber` 以及遥测字段 `battery`、`network`、`charging`、`pendingCount`。响应体带回设备 `status`，设备据此得知自己是否被管理员禁用。

> 被禁用的设备**仍可发心跳**。这是刻意的：心跳是设备唯一能发现自己被恢复的通道，也让管理端能持续区分「禁用但设备还活着」和「禁用且失联」。

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

---

## 管理后台 API

前缀 `/api/admin`，除 `POST /auth/login` 外均需登录 Token。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/login` | 登录，返回 Token |
| POST | `/auth/logout` | 登出，失效 Token |
| GET | `/device/list` | 设备列表 |
| GET | `/device/stats` | 设备统计 |
| GET | `/device/{deviceId}` | 设备详情 |
| PUT | `/device/{deviceId}/enabled` | 启用 / 禁用设备 |
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
    username: root
    password: root
  data:
    redis:
      host: localhost
      port: 6379

app:
  secret:
    key: sms-gateway-secret-key-2024   # 设备 Token 的 HMAC 盐
  api-key:
    cache-ttl-seconds: 60              # 密钥校验结果在 Redis 的缓存时长
  admin:
    default-username: admin            # 首次启动播种的管理员
    default-password: zaq1,lp-
    token-ttl-seconds: 7200            # 登录 Token 有效期
```

所有配置项均可通过环境变量覆盖（`SPRING_DATASOURCE_URL`、`SPRING_REDIS_HOST` 等），`docker/docker-compose.yml` 里即为示例。

### 管理后台 `management/vite.config.ts`

开发服务器端口 `5173`，`/api/` 代理到 `http://localhost:8080`。

---

## 安全注意事项

**部署前请务必处理以下几项：**

1. **修改默认管理员口令。** `application.yml` 里的 `app.admin.default-password: zaq1,lp-` 是明文默认值，生产环境必须通过环境变量覆盖，且只在 `admin_user` 表为空时生效——建库后改配置不会更新已存在的账号。

2. **更换 `app.secret.key`。** 该值用于设备 Token 的 HMAC，泄漏后可伪造任意设备身份，同样建议走环境变量。

3. **API Key 在库中是明文存储的。** 管理后台列表默认打码，但支持「点击显示完整值」，因此无法只存哈希。库被读走等同于密钥全部泄漏——这是内部系统的取舍，请相应收紧数据库访问权限。

4. **Android 客户端允许明文 HTTP。** `network_security_config.xml` 允许访问任意主机，以支持内网 IP 部署；代价是一张二维码即构成未认证的配置注入通道——恶意二维码可把设备指向攻击者的服务器，此后每条短信和验证码都会被截走。因此客户端解码二维码后**不自动保存**，必须由用户核对完整地址并确认。

5. **不要提交签名材料。** `android/app/release.jks` 与 `android/app/keystore.properties` 已加入 `.gitignore`。签名私钥入库意味着他人可签出能覆盖安装的包，请确保它们从未被 `git add`。

6. **CORS 当前为 `allowedOrigins("*")`**（`WebConfig`），生产环境建议收敛为管理后台的实际域名。

7. **建议全站 HTTPS。** 短信正文与验证码在明文 HTTP 下可被中间人读取。

---

## 开发与测试

```bash
# 后端测试（规则引擎、上报服务）
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
