#!/bin/sh
# Reposilite × Cloudflare R2 启动脚本
#
# 1. 校验环境变量
# 2. 生成 shared configuration：所有仓库都用 S3 存储，共用同一个 R2 桶（按仓库名分目录）
# 3. 追加 R2 所需的 JVM / 启动参数，交给官方 entrypoint（/app/entrypoint.sh）启动
set -eu

CONFIG_FILE=/tmp/configuration.shared.json

log() { printf '[r2] %s\n' "$*"; }
die() { printf '[r2] 错误：%s\n' "$*" >&2; exit 1; }
# 转义 JSON 字符串中的 \ 和 "
json_str() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

# ---------- 环境变量 ----------
R2_ACCOUNT_ID=${R2_ACCOUNT_ID:-}
R2_ENDPOINT=${R2_ENDPOINT:-}
R2_ACCESS_KEY_ID=${R2_ACCESS_KEY_ID:-}
R2_SECRET_ACCESS_KEY=${R2_SECRET_ACCESS_KEY:-}
R2_BUCKET=${R2_BUCKET:-}
R2_PREFIX=${R2_PREFIX:-}
ADMIN_NAME=${REPOSILITE_ADMIN_NAME:-admin}
ADMIN_SECRET=${REPOSILITE_ADMIN_SECRET:-}
REPOSITORIES=${REPOSILITE_REPOSITORIES:-releases,snapshots,private:private}

if [ -z "$R2_ENDPOINT" ]; then
  case "$R2_ACCOUNT_ID" in
    '') die "请设置 R2_ACCOUNT_ID（Cloudflare 账户 ID）" ;;
    *[!0-9a-fA-F]*) die "R2_ACCOUNT_ID 应为 32 位十六进制的账户 ID，当前为：$R2_ACCOUNT_ID" ;;
  esac
  R2_ENDPOINT="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com"
fi
R2_ENDPOINT=${R2_ENDPOINT%/}
case "$R2_ENDPOINT" in http://*|https://*) ;; *) die "R2_ENDPOINT 必须以 http:// 或 https:// 开头：$R2_ENDPOINT" ;; esac

[ -n "$R2_ACCESS_KEY_ID" ]     || die "请设置 R2_ACCESS_KEY_ID"
[ -n "$R2_SECRET_ACCESS_KEY" ] || die "请设置 R2_SECRET_ACCESS_KEY"
case "$R2_BUCKET" in
  '') die "请设置 R2_BUCKET（需事先在 R2 控制台创建）" ;;
  *[!a-z0-9.-]*) die "R2_BUCKET 只能包含小写字母、数字、- 和 .：$R2_BUCKET" ;;
esac
R2_PREFIX=${R2_PREFIX#/}
R2_PREFIX=${R2_PREFIX%/}
case "$R2_PREFIX" in *[!A-Za-z0-9._/-]*) die "R2_PREFIX 只能包含字母、数字和 . _ - /：$R2_PREFIX" ;; esac
case "$ADMIN_NAME" in ''|*[!A-Za-z0-9._-]*) die "REPOSILITE_ADMIN_NAME 只能包含字母、数字和 . _ -：$ADMIN_NAME" ;; esac
case "$ADMIN_SECRET" in
  '') die "请设置 REPOSILITE_ADMIN_SECRET（管理员令牌密钥，可用 openssl rand -hex 24 生成）" ;;
  *[!A-Za-z0-9._~@%+=,:!^-]*) die "REPOSILITE_ADMIN_SECRET 含不支持的字符（空格、引号、\$、* 等），建议用 openssl rand -hex 24 生成" ;;
esac
[ ${#ADMIN_SECRET} -ge 16 ] || log "警告：REPOSILITE_ADMIN_SECRET 少于 16 位，公网部署请换成更长的随机密钥"

# ---------- 生成 shared configuration ----------
endpoint=$(json_str "$R2_ENDPOINT")
access_key=$(json_str "$R2_ACCESS_KEY_ID")
secret_key=$(json_str "$R2_SECRET_ACCESS_KEY")

repos_json=
summary=
seen=' '
set -f; old_ifs=$IFS; IFS=,
for entry in $REPOSITORIES; do
  entry=$(printf '%s' "$entry" | tr -d ' \t')
  [ -n "$entry" ] || continue
  id=${entry%%:*}
  case "$entry" in *:*) visibility=${entry#*:} ;; *) visibility=public ;; esac
  visibility=$(printf '%s' "$visibility" | tr '[:lower:]' '[:upper:]')

  case "$id" in ''|*[!A-Za-z0-9._-]*) die "仓库名不合法：'$id'" ;; esac
  case "$visibility" in PUBLIC|HIDDEN|PRIVATE) ;; *) die "仓库 $id 的可见性只能是 public / hidden / private，当前为：$visibility" ;; esac
  case "$seen" in *" $id "*) die "仓库名重复：$id" ;; esac
  seen="$seen$id "

  repos_json="$repos_json${repos_json:+,}
      {
        \"id\": \"$id\",
        \"visibility\": \"$visibility\",
        \"storageProvider\": {
          \"type\": \"s3\",
          \"endpoint\": \"$endpoint\",
          \"region\": \"auto\",
          \"signer\": \"LEGACY_V4\",
          \"accessKey\": \"$access_key\",
          \"secretKey\": \"$secret_key\",
          \"bucketName\": \"$R2_BUCKET\",
          \"prefix\": \"$R2_PREFIX\",
          \"sharedBucket\": true
        }
      }"
  summary="${summary:+$summary }$id($visibility)"
done
IFS=$old_ifs; set +f
[ -n "$repos_json" ] || die "REPOSILITE_REPOSITORIES 不能为空"

# 文件含密钥：先删后建（避开 /tmp 的 protected_regular 限制），仅属主可读
rm -f "$CONFIG_FILE"
(umask 077; printf '{\n  "maven": {\n    "repositories": [%s\n    ]\n  }\n}\n' "$repos_json" > "$CONFIG_FILE")
# 官方 entrypoint 会切换到 reposilite 用户（默认 uid/gid 977）运行，需要能读到配置；
# 它只在数据卷属主不对（通常是首次启动）时才修正日志目录，重建容器后日志目录又是 root 的，这里每次都修正
if [ "$(id -u)" = 0 ]; then
  chown "${PUID:-977}:${PGID:-977}" "$CONFIG_FILE"
  if [ -d /var/log/reposilite ]; then chown -R "${PUID:-977}:${PGID:-977}" /var/log/reposilite; fi
fi

# ---------- 启动参数 ----------
# 官方 entrypoint 只在 REPOSILITE_OPTS 不含 "-wd" 子串时才设置工作目录，
# 这里显式指定，避免密钥里恰好含 "-wd" 时数据写到卷外
case " ${REPOSILITE_OPTS:-} " in
  *" --working-directory"*|*" -wd"*) wd_opt= ;;
  *) wd_opt='--working-directory=/app/data ' ;;
esac
export REPOSILITE_OPTS="${wd_opt}--shared-configuration=$CONFIG_FILE --token=$ADMIN_NAME:$ADMIN_SECRET ${REPOSILITE_OPTS:-}"
# path-style 访问；R2 桶需事先创建（桶级「对象读和写」令牌无权建桶）；
# 构件缓存 1 天（Reposilite 默认 1 小时，maven-metadata.xml 始终不缓存）。用户的 JAVA_OPTS 在后面，可覆盖这些默认值
export JAVA_OPTS="-Dreposilite.s3.pathStyleAccessEnabled=true -Dreposilite.s3.skip-bucket-creation=true -Dreposilite.maven.maxAge=86400 ${JAVA_OPTS:-}"

log "端点：$R2_ENDPOINT"
log "存储：$R2_BUCKET/${R2_PREFIX:+$R2_PREFIX/}<仓库名>/"
log "仓库：$summary"
log "管理员令牌：$ADMIN_NAME"

exec /app/entrypoint.sh
