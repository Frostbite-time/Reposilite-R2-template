ARG REPOSILITE_VERSION=3.6.3
FROM dzikoysk/reposilite:${REPOSILITE_VERSION} AS reposilite-base

# Compile only the extension against the exact SDK/SQLite already in the original JAR.
FROM eclipse-temurin:21-jdk AS quota-build
COPY --from=reposilite-base /app/reposilite.jar /build/reposilite.jar
COPY quota-guard/ /build/quota-guard/
WORKDIR /build
RUN mkdir -p classes \
    && find quota-guard/src/main/java -name '*.java' > sources.txt \
    && javac --release 11 -encoding UTF-8 -cp reposilite.jar -d classes @sources.txt \
    && cp -R quota-guard/src/main/resources/. classes/ \
    && jar --create --file r2-quota.jar -C classes .

FROM reposilite-base

# AWS SDK 2.30+ 默认给请求附加 CRC 校验和，部分 S3 兼容存储（含 R2）处理不好，改为仅在必需时计算
ENV AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED \
    AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED

COPY r2-entrypoint.sh /app/r2-entrypoint.sh
COPY quota-control.sh /app/quota-control
COPY --from=quota-build /build/r2-quota.jar /app/r2-quota.jar
# 兼容在 Windows 上检出（CRLF）的脚本
RUN sed -i 's/\r$//' /app/r2-entrypoint.sh /app/quota-control \
    && chmod 755 /app/r2-entrypoint.sh /app/quota-control \
    && grep -q -- '-jar reposilite.jar' /app/entrypoint.sh \
    && sed -i 's/-jar reposilite.jar/-cp \/app\/r2-quota.jar:\/app\/reposilite.jar com.frostbite.reposilite.quota.QuotaLauncher/g' /app/entrypoint.sh \
    && grep -q 'com.frostbite.reposilite.quota.QuotaLauncher' /app/entrypoint.sh

ENTRYPOINT ["/app/r2-entrypoint.sh"]
