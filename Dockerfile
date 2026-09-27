FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B -q
COPY src ./src
RUN mvn clean package -DskipTests -B -q

FROM eclipse-temurin:21-jre-alpine
# GnuCOBOL — a real compiler CodeChangeService shells out to (via
# CobolCompileService) to verify a proposed change actually compiles, instead
# of trusting the LLM's own claim that it produced valid COBOL.
RUN apk add --no-cache gnucobol
WORKDIR /app
COPY --from=build /app/target/cobalt-rag-api-1.0.0.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]