FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY target/*.jar /app/app.jar
COPY src/main/resources/ /resources/

# Set environment variables with defaults
ENV TESSDATA_PREFIX=/resources

# Expose the port Spring Boot runs on
EXPOSE 10088

CMD ["java", "-jar", "app.jar"]
