FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:11-jre-focal
WORKDIR /app
COPY --from=build /app/target/Serengeti-*-jar-with-dependencies.jar ./serengeti.jar
EXPOSE 1985
CMD ["java", "-jar", "serengeti.jar"]
