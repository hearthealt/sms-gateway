#!/bin/sh
#
# 发版时改版本号：一条命令把仓库里写死版本号的地方一起改掉。
#
#     ./scripts/set-version.sh 1.0.3        # 参数写成 1.0.3 或 v1.0.3 都行
#
# 然后自己提交、打 tag（tag 是唯一脚本不该替你动的东西）：
#
#     git commit -am "chore(all): 版本号 1.0.3"
#     git tag v1.0.3 && git push origin main v1.0.3
#
# 版本号一共写在三个文件里：
#   1. backend/pom.xml               <version>  —— 同时决定 jar 名
#   2. management/package.json       "version"
#   3. android/app/build.gradle.kts  versionName + versionCode
#
# 两处**故意不写版本**的地方（所以不必改）：backend/Dockerfile 与
# .github/workflows/release.yml 里都用 `sms-gateway-backend-*.jar` 通配符 ——
# 以前写死成 -1.0.0.jar，每发一版漏改一处就是构建失败。
#
# Android 的 versionCode 由版本号算出来：major*10000 + minor*100 + patch
# （1.0.3 → 10003）。系统要求它单调递增，别手工填一个更小的数。
set -e

v="${1#v}"
if [ -z "$v" ]; then
    echo "用法: $0 1.0.3" >&2
    exit 1
fi
if ! echo "$v" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "版本号要写成 1.2.3 这样，收到的是：$v" >&2
    exit 1
fi

major=$(echo "$v" | cut -d. -f1)
minor=$(echo "$v" | cut -d. -f2)
patch=$(echo "$v" | cut -d. -f3)
code=$((major * 10000 + minor * 100 + patch))

root=$(cd "$(dirname "$0")/.." && pwd)

# pom 里有两个 <version>（parent 的 Spring Boot 版本 + 本项目的），所以只改
# 紧跟在 <artifactId>sms-gateway-backend</artifactId> 后面那一行。
sed -i "/<artifactId>sms-gateway-backend<\/artifactId>/{n;s|<version>.*</version>|<version>$v</version>|}" \
    "$root/backend/pom.xml"

sed -i "s|\"version\": \"[^\"]*\"|\"version\": \"$v\"|" "$root/management/package.json"

sed -i "s|versionName = \".*\"|versionName = \"$v\"|" "$root/android/app/build.gradle.kts"
sed -i "s|versionCode = [0-9]*|versionCode = $code|" "$root/android/app/build.gradle.kts"

echo "版本号 → $v（Android versionCode $code）"
grep -n "<version>" "$root/backend/pom.xml" | tail -1
grep -n '"version"' "$root/management/package.json"
grep -n "versionName\|versionCode" "$root/android/app/build.gradle.kts" | tail -2
echo
echo "接着："
echo "  git commit -am \"chore(all): 版本号 $v\""
echo "  git tag v$v && git push origin main v$v"
