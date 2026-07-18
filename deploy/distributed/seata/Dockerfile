FROM apache/seata-server:2.5.0.jdk21

ARG MYSQL_CONNECTOR_VERSION=9.1.0
ARG MYSQL_CONNECTOR_SHA256=8776e2ebc46072c9a47ea59d98298c4273bd9f16a7b26b5dfa4744535aa26c62

ADD https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/${MYSQL_CONNECTOR_VERSION}/mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar /tmp/mysql-connector-j.jar

RUN echo "${MYSQL_CONNECTOR_SHA256}  /tmp/mysql-connector-j.jar" | sha256sum -c - \
    && mv /tmp/mysql-connector-j.jar /seata-server/libs/jdbc/mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar
