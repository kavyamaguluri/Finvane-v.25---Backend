FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/finvane-backend-2.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-jar", "app.jar"]

