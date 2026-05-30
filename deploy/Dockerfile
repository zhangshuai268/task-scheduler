FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="task-scheduler"

RUN apk add --no-cache docker-cli bash python3

WORKDIR /app

COPY target/task-scheduler-1.0.0.jar app.jar

EXPOSE 18200

ENTRYPOINT ["java", \
    "-server", \
    "-Xms256m", \
    "-Xmx1024m", \
    "-XX:+UseG1GC", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "app.jar"]
