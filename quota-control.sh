#!/bin/sh
set -eu
set -- -Dtinylog.writerFile=console -Dtinylog.writerFile.level=off \
  -cp /app/r2-quota.jar:/app/reposilite.jar com.frostbite.reposilite.quota.QuotaMain "$@"
if [ "$(id -u)" = 0 ]; then
  exec runuser -u reposilite -- java "$@"
fi
exec java "$@"
