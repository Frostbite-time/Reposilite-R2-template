ARG REPOSILITE_VERSION=3.6.3
FROM dzikoysk/reposilite:${REPOSILITE_VERSION}

# AWS SDK 2.30+ 默认给请求附加 CRC 校验和，部分 S3 兼容存储（含 R2）处理不好，改为仅在必需时计算
ENV AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED \
    AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED

COPY r2-entrypoint.sh /app/r2-entrypoint.sh
# 兼容在 Windows 上检出（CRLF）的脚本
RUN sed -i 's/\r$//' /app/r2-entrypoint.sh && chmod 755 /app/r2-entrypoint.sh

ENTRYPOINT ["/app/r2-entrypoint.sh"]
