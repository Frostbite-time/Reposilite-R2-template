# Reposilite + Cloudflare R2：自建 Maven 仓库

这个模板帮你在自己的服务器上跑一个 Maven 仓库。仓库程序用的是 [Reposilite](https://reposilite.com)，你上传的 jar、pom 等文件都存放在 Cloudflare R2 里（R2 不收下载流量费）。所有设置都写在一个 `.env` 文件里，填好后一条命令就能启动。

启动后默认有三个仓库：

| 仓库 | 谁能下载 | 适合放什么 |
|---|---|---|
| `releases` | 任何人 | 正式版本 |
| `snapshots` | 任何人 | 快照版本 |
| `private` | 只有持有令牌的人 | 不公开的构件 |

往任何仓库**上传**都需要令牌。

## 开始之前

你需要：

- 一台装好 Docker（含 Compose）的 Linux 服务器
- 一个开通了 R2 的 Cloudflare 账号
- 一个域名（建议准备，用来配置 HTTPS，见[第 4 步](#4-配置域名和-https)）

## 1. 在 Cloudflare 准备好以下信息

1. **创建存储桶**：R2 → 创建存储桶，名字随意，例如 `maven`。
2. **创建 R2 API 令牌**：R2 → 管理 API 令牌 → 创建 API 令牌。权限选「对象读和写」，并只授权给刚才的桶。创建完成后会显示「访问密钥 ID」和「机密访问密钥」。机密访问密钥只显示这一次，请先记下来。
3. **创建账户分析令牌**：右上角头像 → 我的个人资料 → API 令牌 → 创建自定义令牌。权限只需要「账户分析：读取」（Account Analytics: Read）。它用于[免费额度保护](#5-免费额度保护)。
4. **记下账户 ID 和账单日**：账户 ID 在 R2 概览页右侧。账单日是每个月开始新账单周期的那一天，可以在账单（Billing）页面查到。

## 2. 配置并启动

在服务器上下载模板，并复制一份配置文件：

```bash
git clone https://github.com/Frostbite-time/Reposilite-R2-template.git
```

```bash
cd Reposilite-R2-template
```

```bash
cp .env.example .env
```

打开 `.env`，把开头「必填」部分的几项填好。管理员密钥可以用这条命令随机生成：

```bash
openssl rand -hex 24
```

然后启动：

```bash
docker compose up -d
```

用 `docker compose logs -f` 查看启动过程。如果配置缺项或填错，日志里会直接告诉你哪一项有问题。看到 `Listening on http://0.0.0.0:8080/` 就说明启动成功了。

在浏览器打开 `http://服务器IP:8080`，点右上角登录：名称填 `admin`，密钥填你在 `.env` 里设置的 `REPOSILITE_ADMIN_SECRET`。

## 3. 上传和下载构件

### 创建上传用的令牌

管理员令牌的权限太大，不要把它放进 CI 或项目配置里。登录网页后，进入 **Console** 页，依次执行：

```
token-generate ci
route-add ci /releases/ rw
route-add ci /snapshots/ rw
```

第一条命令会输出新令牌的密钥，请记下来。这个 `ci` 令牌只能读写 `releases` 和 `snapshots`。

如果某个项目需要下载 `private` 仓库里的构件，可以单独给它建一个只读令牌：

```
token-generate reader
route-add reader /private/ r
```

### Maven

在 `~/.m2/settings.xml` 里填写令牌：

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

在 `pom.xml` 里指定上传地址：

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

之后执行 `mvn deploy` 就会上传到你的仓库。

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

在 `~/.gradle/gradle.properties` 里写上 `reposiliteUsername=ci` 和 `reposilitePassword=令牌密钥`。在 CI 里也可以改用环境变量 `ORG_GRADLE_PROJECT_reposiliteUsername` 和 `ORG_GRADLE_PROJECT_reposilitePassword`。

### 只下载依赖

只需要添加仓库地址。例如 Gradle：

```kotlin
repositories {
    maven("https://maven.example.com/releases")
}
```

## 4. 配置域名和 HTTPS

对外使用时请务必配置 HTTPS。Maven 3.8.1 及以上版本默认不会从 http 地址下载依赖，而且走 http 时令牌是明文传输的。

做法是让仓库只监听本机，再用反向代理对外提供 HTTPS：

1. 在 `.env` 里设置 `REPOSILITE_PORT=127.0.0.1:8080`，然后执行 `docker compose up -d`。
2. 把你的域名反向代理到 `127.0.0.1:8080`。用 Caddy 的话，配置只需要几行，证书会自动申请：

```
maven.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

如果服务器上已经有 Nginx、1Panel 等在用 80/443 端口，就在它们里面加一个指向 `127.0.0.1:8080` 的反向代理站点。

### 如果域名开启了 Cloudflare 代理（橙色云朵）

- **必须给私有仓库加缓存绕过规则。** Reposilite 会把所有构件都标记为可缓存，私有仓库也不例外；而 Cloudflare 默认会缓存 `.jar` 文件。不加规则的话，有权限的人下载过某个私有 jar 后，其他人不带令牌也可能拿到它。添加方法：Cloudflare → 缓存 → Cache Rules → 创建规则，条件设为「主机名等于你的域名」并且「URI 路径开头为 `/private/`」，缓存资格选「绕过缓存」。如果你另外建了私有仓库，也要一并加进去。
- **建议让 Cloudflare 也缓存 pom 和校验文件。** Cloudflare 默认只缓存 jar 等少数扩展名，而 Maven 请求最多的恰恰是 `.pom`、`.sha1` 这类小文件。可以再建一条规则，用「编辑表达式」粘贴：

  ```
  (http.host eq "maven.example.com" and http.request.uri.path.extension in {"pom" "module" "sha1" "md5" "sha256" "sha512" "asc"} and not starts_with(http.request.uri.path, "/private/"))
  ```

  缓存资格选「符合缓存条件」；边缘 TTL 选「有 cache-control 标头就使用，没有则绕过缓存」。这样设置后，会变化的 `maven-metadata.xml` 和「找不到文件」的响应都不会被缓存。
- **单个文件的上传上限是 100 MB**（Cloudflare 免费版的限制）。更大的文件上传会失败；需要的话，另建一条不开代理（灰色云朵）的域名记录专门用来上传。

## 5. 免费额度保护

R2 按请求次数收费。每个账单周期的免费额度是：

- **A 类请求**（上传、列出目录等）：100 万次
- **B 类请求**（下载、查询文件信息等）：1000 万次

超出的部分要付费。为了避免意外账单，这个模板默认会在用到免费额度的 **90%** 时停止访问 R2。到那时上传和下载都会失败（返回 HTTP 500），但网页仍然可以打开。下个账单周期开始后会自动恢复。

它的工作方式：

- 每次访问 R2 之前先记一次数。额度用完后，请求不会发到 R2。
- 每天用 Cloudflare 的统计数据核对一次用量，所以同一账号下其他服务用掉的次数也会被算进来。这就是需要「账户分析」令牌的原因。
- 每个账单周期开始时，必须先核对一次才会放行。因此每月账单日零点后的几分钟里，仓库会暂时无法上传下载。
- 本地记下的次数会比 Cloudflare 实际计费的略多（例如失败重试也会计数），所以只会更保守，不会少算。

常用操作：

```bash
docker compose exec reposilite /app/quota-control status
```

```bash
docker compose exec reposilite /app/quota-control sync
```

第一条查看本周期的已用量和剩余量，第二条立即和 Cloudflare 核对一次。

需要注意：

- `R2_QUOTA_BILLING_DAY` 要和你在 Cloudflare 的账单日一致，否则统计周期和实际计费周期对不上。
- 用量记录保存在数据卷里（`/app/data/r2-quota`），请不要删除。如果之后修改了账户、端点、账单日或时区，启动时会报错，以免新旧记录混在一起。这种情况下可以先 `docker compose down`，删除这个目录，再重新启动：下次启动时它会从 Cloudflare 重新读取本周期的用量。
- 想调整上限，修改 `R2_QUOTA_CLASS_A_LIMIT` 和 `R2_QUOTA_CLASS_B_LIMIT` 后执行 `docker compose up -d`。如果完全不需要这个功能，设置 `R2_QUOTA_ENABLED=false` 即可。
- 它只保护请求次数。存储容量（每月免费 10 GB）不在保护范围内。

## 配置项一览

**必填**

| 变量 | 说明 |
|---|---|
| `R2_ACCOUNT_ID` | Cloudflare 账户 ID |
| `R2_ACCESS_KEY_ID` | R2 API 令牌的访问密钥 ID |
| `R2_SECRET_ACCESS_KEY` | R2 API 令牌的机密访问密钥 |
| `R2_BUCKET` | 存储桶名称，需要事先创建 |
| `REPOSILITE_ADMIN_SECRET` | 管理员密钥 |
| `CF_R2_ANALYTICS_TOKEN` | 账户分析令牌；关闭额度保护时可以留空 |
| `R2_QUOTA_BILLING_DAY` | Cloudflare 账单日（每月几号），默认 `1` |

**常用可选项**

| 变量 | 默认值 | 说明 |
|---|---|---|
| `REPOSILITE_ADMIN_NAME` | `admin` | 管理员登录名 |
| `REPOSILITE_PORT` | `8080` | 对外端口；只给本机反向代理用时写 `127.0.0.1:8080` |
| `REPOSILITE_REPOSITORIES` | `releases,snapshots,private:private` | 仓库列表，格式为 `名称[:public\|hidden\|private]`，用逗号分隔 |
| `R2_PREFIX` | 空 | 让所有文件放在桶里的某个目录下，和其他服务共用一个桶时使用 |
| `R2_ENDPOINT` | `https://<账户ID>.r2.cloudflarestorage.com` | 欧盟辖区的桶要改成 `https://<账户ID>.eu.r2.cloudflarestorage.com` |
| `JAVA_OPTS` | 空 | JVM 参数，例如 `-Xmx256m` |
| `REPOSILITE_VERSION` | `3.6.3` | Reposilite 版本；修改后要用 `docker compose up -d --build` 重新构建 |
| `R2_QUOTA_ENABLED` | `true` | 是否开启免费额度保护 |
| `R2_QUOTA_CLASS_A_LIMIT` | `900000` | 每个账单周期允许的 A 类请求数；`0` 表示完全禁止 |
| `R2_QUOTA_CLASS_B_LIMIT` | `9000000` | 每个账单周期允许的 B 类请求数；`0` 表示完全禁止 |
| `R2_QUOTA_BILLING_TIMEZONE` | `UTC` | 账单日按哪个时区计算 |

`hidden` 仓库任何人都能按完整路径下载，但没有令牌时不会显示在网页上，也不能浏览目录。

`.env.example` 末尾还列出了几个很少需要改动的额度保护选项，例如核对间隔、令牌文件路径等。

## 日常维护

- **查看日志**：`docker compose logs -f`
- **修改配置**：改完 `.env` 后执行 `docker compose up -d`。网页上的 Settings 页是只读的，因为仓库配置都来自 `.env`。
- **升级**：执行 `git pull`，再执行 `docker compose up -d --build`。
- **数据存放位置**：构件都在 R2 里；令牌、下载统计和用量记录保存在 Docker 数据卷 `reposilite-data` 中。删除这个卷会丢失你创建的令牌。
- **更换管理员密钥**：修改 `REPOSILITE_ADMIN_SECRET`，然后执行 `docker compose up -d`。

## 常见问题

**上传或下载返回 500**：查看日志里以 `[r2-quota]` 开头的行。常见原因有三种：额度已经用完；新的账单周期开始后还没完成第一次核对；账户分析令牌无效。

**上传大文件失败**：如果开启了 Cloudflare 代理，单个文件最大只能 100 MB，见[第 4 步](#如果域名开启了-cloudflare-代理橙色云朵)。

**下载到的还是旧文件**：发布过的版本不允许覆盖，所以正常情况下不会出现。如果你删除某个版本后又用同样的版本号重新上传，Cloudflare 和客户端最多会缓存旧文件 1 天，可以在 Cloudflare 里手动清除缓存。

## 工作原理

改模板之前可以先看看这部分。

- `r2-entrypoint.sh`：容器的启动脚本。它先检查 `.env`，再生成 Reposilite 的存储配置（所有仓库共用一个桶，每个仓库对应桶里的一个目录），然后交给官方启动脚本运行。它还会设置几项 R2 需要的参数：path-style 访问、R2 要求的 `LEGACY_V4` 签名、不自动建桶，以及构件缓存 1 天。
- `quota-guard/`：免费额度保护的 Java 代码。构建镜像时，它会被编译成 `r2-quota.jar`，在 Reposilite 访问 R2 之前完成计数和拦截。Reposilite 本身的程序文件不做任何修改。
- `Dockerfile`：基于官方镜像，加入上面两部分。
- `quota-control.sh`：上文用到的用量查看和核对命令。

额度保护的测试需要 JDK 21+ 和对应版本的 Reposilite jar，在 Linux 或 macOS 上运行：

```bash
sh quota-guard/test.sh /path/to/reposilite.jar
```
