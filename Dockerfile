# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./
# Download dependencies (cached layer if pom.xml unchanged)
RUN ./mvnw dependency:go-offline -q

COPY src ./src
RUN ./mvnw package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S yadony && adduser -S yadony -G yadony

COPY --from=builder /build/target/yadony-back-*.jar app.jar

RUN chown yadony:yadony app.jar
USER yadony

EXPOSE 8080

# noOpenSsl : le client Firestore passe par gRPC, qui tente de charger
# netty-tcnative (OpenSSL natif). Cette bibliothèque est compilée pour la glibc
# alors que cette image est Alpine/musl : son chargement tue la JVM en SIGSEGV,
# dans JNI_OnLoad, avant même que Spring ne puisse rapporter quoi que ce soit.
# Le drapeau force gRPC sur l'implémentation SSL du JDK, qui n'a pas de partie
# native. Le crash est resté invisible tant que le bean Firestore était nul et
# que rien n'ouvrait donc de canal gRPC.
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Dio.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl=true", \
  "-jar", "app.jar"]
