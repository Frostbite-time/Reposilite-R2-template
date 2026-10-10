# Reposilite × Cloudflare R2

用 [Reposilite](https://reposilite.com) 搭一个以 **Cloudflare R2** 为存储后端的私有 Maven 仓库：填几个环境变量，一条命令启动。

- 所有构件存进**同一个 R2 桶**，按仓库名分目录：`<桶>/releases/…`、`<桶>/snapshots/…`、`<桶>/private/…`
- 仓库配置由环境变量在每次启动时生成，服务器本地只保存令牌、统计等少量元数据
- 基于官方镜像 `dzikoysk/reposilite:3.6.3`，使用 Reposilite 原生 S3 存储（R2 所需的 `LEGACY_V4` 签名、path-style 均已配好）
- 独立扩展 JAR 在每次 R2 发送前执行 A/B 月度额度墙，并每天校准账户云端用量；不修改或重新编译 Reposilite 原始 JAR

## 快速开始

### 1. 准备 R2

1. Cloudflare 控制台 → **R2** → 创建存储桶，例如 `maven`
2. R2 → **管理 API 令牌** → 创建 API 令牌：权限选 **对象读和写**，并指定到上面的桶
3. 记下 **访问密钥 ID**、**机密访问密钥**，以及 R2 概览页上的 **账户 ID**
4. 在 Cloudflare 的 API Token 页面创建另一个 Token，权限仅需当前账户的 **Account Analytics → Read**，用于读取操作统计
5. 确认 R2 的实际计费周期起始日期，填入 `R2_QUOTA_BILLING_DAY`（例如每月 27 日填 `27`）

### 2. 配置并启动

在服务器上 clone 本仓库并进入目录，然后：

```bash
cp .env.example .env
```

编辑 `.env`，填好原有 5 项：`R2_ACCOUNT_ID`、`R2_ACCESS_KEY_ID`、`R2_SECRET_ACCESS_KEY`、`R2_BUCKET`、`REPOSILITE_ADMIN_SECRET`（可用 `openssl rand -hex 24` 生成），再填 `CF_R2_ANALYTICS_TOKEN` 和实际的 `R2_QUOTA_BILLING_DAY`。然后：

```bash
docker compose up -d
```

打开 `http://<服务器IP>:8080`，右上角登录：名称 `admin`，密钥为 `REPOSILITE_ADMIN_SECRET`。

<details>
<summary>不用 docker compose</summary>

```bash
docker build -t reposilite-r2 .
```

```bash
docker run -d --name reposilite --restart unless-stopped --env-file .env -p 8080:8080 -v reposilite-data:/app/data reposilite-r2
```

</details>

## 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|---|:-:|---|---|
| `R2_ACCOUNT_ID` | ✅ | | Cloudflare 账户 ID |
| `R2_ACCESS_KEY_ID` | ✅ | | R2 API 令牌的访问密钥 ID |
| `R2_SECRET_ACCESS_KEY` | ✅ | | R2 API 令牌的机密访问密钥 |
| `R2_BUCKET` | ✅ | | 桶名，需事先创建 |
| `REPOSILITE_ADMIN_SECRET` | ✅ | | 管理员令牌密钥 |
| `REPOSILITE_ADMIN_NAME` | | `admin` | 管理员令牌名 |
| `REPOSILITE_PORT` | | `8080` | 宿主机端口；只给本机反代用可写 `127.0.0.1:8080` |
| `REPOSILITE_REPOSITORIES` | | `releases,snapshots,private:private` | 仓库列表，格式 `名称[:可见性]` |
| `R2_PREFIX` | | 空 | 桶内对象前缀，与其他服务共用桶时使用 |
| `R2_ENDPOINT` | | `https://<账户ID>.r2.cloudflarestorage.com` | 自定义端点，如欧盟辖区 `https://<账户ID>.eu.r2.cloudflarestorage.com` |
| `JAVA_OPTS` | | | JVM 参数，如 `-Xmx256m`；构件缓存时间默认 1 天，可用 `-Dreposilite.maven.maxAge=<秒>` 修改 |
| `REPOSILITE_VERSION` | | `3.6.3` | Reposilite 版本，修改后 `docker compose up -d --build` |

可见性：`public` 任何人可读、可浏览；`hidden` 任何人可按完整路径下载，但无令牌时不显示、不能浏览目录；`private` 需令牌才能读。**部署（写入）始终需要令牌。**

## 月度操作额度墙

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `R2_QUOTA_ENABLED` | `true` | 启用额度墙；明确设为 `false` 才关闭 |
| `R2_QUOTA_CLASS_A_LIMIT` | `900000` | 本计费周期 A 类上限；`0` 拒绝全部 A 类调用 |
| `R2_QUOTA_CLASS_B_LIMIT` | `9000000` | 本计费周期 B 类上限；`0` 拒绝全部 B 类调用 |
| `R2_QUOTA_BILLING_DAY` | `1` | 每月额度重置日，必须与 CF 实际计费周期一致；不足该日期的月份取月末 |
| `R2_QUOTA_BILLING_TIMEZONE` | `UTC` | 重置日的时区，额度在当天午夜重置 |
| `CF_R2_ANALYTICS_TOKEN` | 空 | 当前账户 `Account Analytics → Read` Token；与 R2 S3 密钥独立 |
| `CF_R2_ANALYTICS_TOKEN_FILE` | 空 | 可选容器内 Token 文件，优先于直接传入的 Token |
| `R2_QUOTA_SYNC_ENABLED` | `true` | 自动云端校准 |
| `R2_QUOTA_SYNC_INTERVAL_SECONDS` | `86400` | 两次成功自动校准之间的间隔，最小 60 秒；失败每分钟重试 |
| `R2_QUOTA_SYNC_LAG_SECONDS` | `300` | 云端查询截止时间向前留出的窗口；不是 CF 对数据延迟的承诺 |
| `R2_QUOTA_REQUIRE_INITIAL_SYNC` | `true` | 每个新周期先完成云端初始化，再放行 A/B 调用 |
| `R2_QUOTA_DB` | `/app/data/r2-quota/quota.db` | 独立 SQLite 台账，必须位于持久化数据卷内 |

修改 `.env` 后执行 `docker compose up -d`，A/B 上限立即按新配置执行，历史计数保留。修改扩展源码后需执行 `docker compose up -d --build`。

本地计数在 **AWS SDK `beforeTransmission`** 阶段执行：先用 SQLite 原子事务扣额度并持久化，再发送。每页 LIST、每次重试、每个分片分别计算；失败或尚未真正到达 R2 的发送尝试不退额度，所以本地计数可以高于 CF 实际计费用量。未知操作直接拒绝，数据库故障直接停止计费调用。免费删除操作不扣 A/B 额度，但 Reposilite 删除目录前的 LIST 仍然需要 A 类额度。

每日校准按**整个账户、本计费周期**查询 `r2OperationsAdaptiveGroups`，以短时间窗口汇总各操作类型。SQLite 保存每分钟的本地发送计数；云端总量减去同一时间范围内已统计的本地调用，得到校准差额。差额只增不减，查询期间及截止时间后的本地调用继续保留；重复同步不会重复加总，云端数值降低不会返还额度。任何查询窗口失败，整次同步均不更新台账。GraphQL 查询本身不消耗 R2 A/B 操作额度。

首次同步失败或缺少统计 Token 时，网页可以启动，但计费的 R2 操作被阻止；旧周期已完成初始化后，后续同步失败会保留现有台账并重试。周期切换后等待新周期初始化；默认延迟窗口可能让月初的最初几分钟无法回源。日志以 `[r2-quota]` 开头；达到额度或台账故障时，当前 Reposilite 可能向客户端返回存储错误（HTTP 500），不会继续访问 R2。

查看额度和手动同步：

```bash
docker compose exec reposilite /app/quota-control status
docker compose exec reposilite /app/quota-control sync
```

状态包含周期起止、A/B 上限、已用/剩余、本地计数、云端校准差额及上次成功同步时间，不输出密钥。不要删除台账、切换数据库路径或从旧备份恢复计数；更改账户、端点或计费日/时区会触发台账身份校验，需明确迁移后才能继续启动。

默认上限为 R2 **Standard** 免费操作额度的 90%。剩余 10% 用于容纳统计误差、延迟及其他途径的额外操作，CF 没有承诺统计误差小于 10%。此扩展约束经该 Reposilite 发出的调用；其他服务、控制台、生命周期等调用必须纳入预算。存储容量及 Infrequent Access 费用不属于此操作额度墙。

参考：[R2 操作分类及免费额度](https://developers.cloudflare.com/r2/pricing/)、[R2 统计 API](https://developers.cloudflare.com/r2/platform/metrics-analytics/)、[统计 Token 权限](https://developers.cloudflare.com/analytics/graphql-api/getting-started/authentication/api-token-auth/)、[分析数据采样](https://developers.cloudflare.com/analytics/graphql-api/sampling/)。

### 核心测试

只有一个核心测试文件，覆盖并发、重启、月度切换、同步不重复/不退额，以及真实 AWS SDK 的拦截器加载、重试、分页和数据库失败阻断；HTTP 测试均指向本地模拟端点。

```bash
# 在装有 JDK 21+ 的环境，用对应版本的原始 Reposilite JAR 编译和运行
sh quota-guard/test.sh /absolute/path/reposilite.jar
```

## 发布构件

### 创建部署令牌

管理员令牌权限过大，不要放进 CI。登录网页后进入 **Console** 页执行：

```
token-generate ci
route-add ci /releases/ rw
route-add ci /snapshots/ rw
```

第一条命令会输出令牌密钥。只需读取 private 仓库的令牌：`token-generate reader` + `route-add reader /private/ r`。

### Maven

`~/.m2/settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>reposilite</id>
      <username>ci</username>
      <password>令牌密钥</password>
    </server>
  </servers>
</settings>
```

`pom.xml`：

```xml
<distributionManagement>
  <repository>
    <id>reposilite</id>
    <url>https://maven.example.com/releases</url>
  </repository>
  <snapshotRepository>
    <id>reposilite</id>
    <url>https://maven.example.com/snapshots</url>
  </snapshotRepository>
</distributionManagement>
```

### Gradle（Kotlin DSL）

```kotlin
publishing {
    repositories {
        maven {
            name = "reposilite"
            val repo = if (version.toString().endsWith("SNAPSHOT")) "snapshots" else "releases"
            url = uri("https://maven.example.com/$repo")
            credentials(PasswordCredentials::class)
        }
    }
}
```

在 `~/.gradle/gradle.properties` 中写入 `reposiliteUsername=ci` 和 `reposilitePassword=令牌密钥`（CI 中可用环境变量 `ORG_GRADLE_PROJECT_reposiliteUsername` / `ORG_GRADLE_PROJECT_reposilitePassword`）。

依赖方只需添加仓库地址，如 Gradle 的 `maven("https://maven.example.com/releases")`。

## 注意事项

- **务必配置 HTTPS**：Maven 3.8.1+ 默认阻止从 http 仓库拉取依赖，而且令牌会明文传输。建议把 `REPOSILITE_PORT` 设为 `127.0.0.1:8080`，前面加反向代理，例如 Caddy：
  ```
  maven.example.com {
      reverse_proxy 127.0.0.1:8080
  }
  ```
- **缓存**：jar、pom 等构件文件响应 `Cache-Control: public, max-age=86400`（1 天）。发布后的版本不可覆盖（snapshot 文件名自带时间戳），长缓存是安全的；会变化的 `maven-metadata.xml` 及其校验文件始终不缓存。
- **网页 Settings 页为只读**：仓库配置由环境变量生成。修改 `.env` 后执行 `docker compose up -d` 即可重建生效；需要更多定制（如代理 Maven Central）可直接修改 `r2-entrypoint.sh` 中生成的 JSON。
- **数据位置**：构件在 R2；令牌和下载统计在数据卷 `reposilite-data`（SQLite）中，不要随意删除该卷。
- **管理员令牌**是每次启动时按环境变量创建的临时令牌，修改 `.env` 并重建容器即可轮换。
- **排错**：`docker compose logs -f`。启动时会先输出 `[r2]` 开头的配置摘要；环境变量缺失或格式不对会直接报错退出。

## 使用 Cloudflare 代理（橙色云朵）时

- **私有仓库必须绕过缓存**：Reposilite 对所有构件文件都返回 `Cache-Control: public`，不区分仓库是否私有，而 Cloudflare 默认缓存 `.jar`。不加规则的话，有权限的人下载过的私有 jar 会被缓存，之后匿名用户也能直接拿到。在 **缓存 → Cache Rules** 新建规则：条件为「主机名等于你的域名」且「URI 路径开头为 `/private/`」（其他私有仓库同理），缓存资格选 **绕过缓存**。
- **缓存 pom 和校验文件（可选）**：Cloudflare 默认只缓存 jar 等扩展名，而 Maven 请求最多的 `.pom`、`.sha1` 等默认不缓存。可再建一条规则，表达式：
  ```
  (http.host eq "maven.example.com" and http.request.uri.path.extension in {"pom" "module" "sha1" "md5" "sha256" "sha512" "asc"} and not starts_with(http.request.uri.path, "/private/"))
  ```
  缓存资格选 **符合缓存条件**，边缘 TTL 选「有 cache-control 标头就使用，没有则绕过缓存」。这样 `maven-metadata.xml` 的校验文件和 404 响应都不会被缓存。
- **上传上限**：免费套餐单个请求体最大 100 MB，更大的构件上传会返回 413。有需要的话，另建一条不开代理（灰色云朵）的 DNS 记录专门用于上传。

## 工作原理

| 文件 | 作用 |
|---|---|
| `r2-entrypoint.sh` | 校验环境变量 → 生成 Reposilite shared configuration → 调用官方 entrypoint |
| `Dockerfile` | 基于官方镜像，加入上面的脚本 |
| `quota-guard/` | 编译为独立的 `r2-quota.jar`，包含 SDK 拦截、持久化额度台账、每日校准和一个核心测试 |
| `quota-control.sh` | 读取台账状态或立即同步；以 Reposilite 用户运行，避免破坏台账文件权限 |
| `docker-compose.yml` | 一键启动（端口、数据卷、重启策略） |
| `.env.example` | 环境变量模板 |

启动脚本为每个仓库生成如下存储配置，通过 `--shared-configuration` 交给 Reposilite，并用 `--token` 创建管理员令牌：

```json
{
  "id": "releases",
  "visibility": "PUBLIC",
  "storageProvider": {
    "type": "s3",
    "endpoint": "https://<账户ID>.r2.cloudflarestorage.com",
    "region": "auto",
    "signer": "LEGACY_V4",
    "accessKey": "…",
    "secretKey": "…",
    "bucketName": "maven",
    "prefix": "",
    "sharedBucket": true
  }
}
```

同时附加 JVM 参数 `-Dreposilite.s3.pathStyleAccessEnabled=true -Dreposilite.s3.skip-bucket-creation=true -Dreposilite.maven.maxAge=86400`（桶级令牌无权建桶，所以不自动建桶；构件缓存 1 天，Reposilite 默认只有 1 小时），并设置 `AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED`，避免新版 AWS SDK 默认的 CRC 校验头在 R2 上出问题。

镜像构建时只编译扩展，把官方启动脚本的 `java -jar reposilite.jar` 改成同时加载两个 JAR 的 classpath 启动。`QuotaLauncher` 先初始化台账与校准任务，验证 AWS SDK 能发现全局拦截器，再调用原始 JAR Manifest 中的入口类。原始 `reposilite.jar` 的内容保持不变。
