FROM eclipse-temurin:17-jre

ARG SENTINEL_VERSION=1.8.9

ADD https://github.com/alibaba/Sentinel/releases/download/${SENTINEL_VERSION}/sentinel-dashboard-${SENTINEL_VERSION}.jar /opt/sentinel-dashboard.jar

EXPOSE 8858

ENTRYPOINT ["java", "-Dserver.port=8858", "-Dcsp.sentinel.dashboard.server=localhost:8858", "-Dproject.name=sentinel-dashboard", "-jar", "/opt/sentinel-dashboard.jar"]
