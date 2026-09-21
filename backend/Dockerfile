FROM eclipse-temurin:17-jre-alpine

# 基础镜像默认 UTC，而代码里所有「今天 / 现在」都用 JVM 默认时区
# （LocalDate.now()、ZoneId.systemDefault()）。不设的话，北京时间凌晨收到的短信
# 会被算进前一天的「今日短信」，列表时间也整体偏 8 小时。
# 注意：JDBC URL 上的 serverTimezone=Asia/Shanghai **不能**替代这一条 ——
# 它只影响 Instant 与时间戳的转换，不改变 LocalDateTime 的字面存取。
ENV TZ=Asia/Shanghai

WORKDIR /app

# 用通配符，不写死版本号：jar 名由 pom 的 <version> 决定，写死的话每发一版都要改这里
# （漏改就是构建失败）。**target 里只能有一个 jar** —— 换了版本又没 clean 的话会有两个，
# 那时 docker 会直接报「more than one source file」而不是悄悄拿错一个。
COPY target/sms-gateway-backend-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]