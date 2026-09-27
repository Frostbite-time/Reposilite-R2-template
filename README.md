# Reposilite × Cloudflare R2

用 [Reposilite](https://reposilite.com) 搭一个以 **Cloudflare R2** 为存储后端的私有 Maven 仓库：填几个环境变量，一条命令启动。

- 所有构件存进**同一个 R2 桶**，按仓库名分目录：`<桶>/releases/…`、`<桶>/snapshots/…`、`<桶>/private/…`
- 仓库配置由环境变量在每次启动时生成，服务器本地只保存令牌、统计等少量元数据
- 基于官方镜像 `dzikoysk/reposilite:3.6.3`，使用 Reposilite 原生 S3 存储（R2 所需的 `LEGACY_V4` 签名、path-style 均已配好）

## 快速开始

### 1. 准备 R2

1. Cloudflare 控制台 → **R2** → 创建存储桶，例如 `maven`
2. R2 → **管理 API 令牌** → 创建 API 令牌：权限选 **对象读和写**，并指定到上面的桶
3. 记下 **访问密钥 ID**、**机密访问密钥**，以及 R2 概览页上的 **账户 ID**

### 2. 配置并启动

在服务器上 clone 本仓库并进入目录，然后：

```bash
cp .env.example .env
```

编辑 `.env`，填好 5 个必填项：`R2_ACCOUNT_ID`、`R2_ACCESS_KEY_ID`、`R2_SECRET_ACCESS_KEY`、`R2_BUCKET`、`REPOSILITE_ADMIN_SECRET`（可用 `openssl rand -hex 24` 生成）。然后：

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
