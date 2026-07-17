# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
# 래퍼와 빌드 스크립트를 먼저 복사해서, 소스만 바뀌면 의존성 레이어를 재사용한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon
COPY src src
# 테스트는 CI에서 Testcontainers로 이미 돌았다. 이미지 빌드에서 또 돌리지 않는다.
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# root로 돌리지 않는다.
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/libs/*.jar app.jar
USER app
EXPOSE 8080
# 컨테이너 메모리 한도를 JVM이 인식하게 한다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
