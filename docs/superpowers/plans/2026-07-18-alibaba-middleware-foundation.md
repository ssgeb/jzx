# Alibaba Middleware Microservices Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the current application into a buildable transitional Maven reactor and start four empty business services that register with Nacos and include Sentinel and Seata foundations, without Gateway or OpenFeign.

**Architecture:** Move the current monolith into a temporary `legacy-service` module, then add shared libraries and four Spring Boot service modules. Nacos provides registration/configuration, Sentinel protects HTTP and external-call resources, and Seata infrastructure is present for later real multi-datasource transactions. Kafka remains the cross-service event transport.

**Tech Stack:** Java 17, Spring Boot 3.5.0, Spring Cloud 2025.0.0, Spring Cloud Alibaba 2025.0.0.0, MyBatis-Plus 3.5.17, Nacos 3.0.3, Sentinel 1.8.9, Seata 2.5.0, Maven 3.9.6, Docker Compose.

---

## File map

### Files moved

- Move: `src/` → `legacy-service/src/`

### Files created

- `legacy-service/pom.xml`
- `platform-common/pom.xml`
- `platform-security/pom.xml`
- `event-contracts/pom.xml`
- `auth-service/pom.xml`
- `resource-service/pom.xml`
- `detection-service/pom.xml`
- `assistant-service/pom.xml`
- One Spring Boot application class and `application.yml` for each business service
- Shared response, request-context and event-envelope classes
- `deploy/distributed/compose.yml`
- `deploy/distributed/.env.example`
- `deploy/distributed/mysql-init/00-create-databases.sh`
- `deploy/distributed/mysql-init/01-seata-server.sql`
- `deploy/distributed/sentinel/Dockerfile`
- `deploy/distributed/seata/application.yml`
- `scripts/distributed/smoke.ps1`
- `docs/distributed-development.md`

### Files modified

- `pom.xml`
- `.gitignore`
- `.env.example`
- `README.md`

---

### Task 1: Record the pre-migration build baseline

**Files:**

- Create: `docs/distributed-baseline.md`

- [x] **Step 1: Run the existing backend tests**

Run:

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' test
```

Expected: Maven exits with code `0`. If an existing test fails, record its exact class and failure in the baseline document before changing dependencies.

- [x] **Step 2: Run the existing frontend tests**

Run:

```powershell
Set-Location frontend
node --test tests/*.test.cjs
Set-Location ..
```

Expected: the existing frontend contract suite exits with code `0`.

- [x] **Step 3: Write the baseline record**

Create `docs/distributed-baseline.md` with this structure and the actual command results:

```markdown
# 分布式改造基线

- 分支：feature/microservices-distributed
- Maven：D:\ruanjian\apache-maven-3.9.6
- Java：17
- 后端测试：通过数量和失败数量
- 前端测试：通过数量和失败数量
- 已知外部依赖：MySQL、Redis、Kafka、OSS、Mem0、Chroma、DeepSeek
- 基线提交：执行时的 `git rev-parse HEAD`
```

- [x] **Step 4: Commit the baseline**

```powershell
git add docs/distributed-baseline.md
git commit -m "docs: record distributed migration baseline"
```

---

### Task 2: Add a failing Maven topology contract

**Files:**

- Create: `src/test/java/com/ruanzhu/doorhandlecatch/config/MicroserviceModuleTopologyContractTest.java`

- [ ] **Step 1: Write the failing contract test**

```java
package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MicroserviceModuleTopologyContractTest {

    private final Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));

    @Test
    void rootPomDeclaresTransitionalMicroserviceModules() throws Exception {
        String pom = Files.readString(root.resolve("pom.xml"));

        assertThat(pom).contains("<packaging>pom</packaging>");
        assertThat(pom).contains("<module>legacy-service</module>");
        assertThat(pom).contains("<module>platform-common</module>");
        assertThat(pom).contains("<module>platform-security</module>");
        assertThat(pom).contains("<module>event-contracts</module>");
        assertThat(pom).contains("<module>auth-service</module>");
        assertThat(pom).contains("<module>resource-service</module>");
        assertThat(pom).contains("<module>detection-service</module>");
        assertThat(pom).contains("<module>assistant-service</module>");
    }
}
```

- [ ] **Step 2: Run the test and verify the intended failure**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -Dtest=MicroserviceModuleTopologyContractTest test
```

Expected: FAIL because the root project still has `<packaging>jar</packaging>` and no module list.

- [ ] **Step 3: Commit the red test**

```powershell
git add src/test/java/com/ruanzhu/doorhandlecatch/config/MicroserviceModuleTopologyContractTest.java
git commit -m "test: define microservice module topology"
```

---

### Task 3: Convert the root project into a Maven reactor

**Files:**

- Modify: `pom.xml`
- Create: `legacy-service/pom.xml`
- Move: `src/` → `legacy-service/src/`

- [ ] **Step 1: Move the current application into the transitional module**

Use `git mv` so history remains visible:

```powershell
New-Item -ItemType Directory -Path legacy-service
git mv src legacy-service/src
```

- [ ] **Step 2: Replace the root POM with the parent reactor**

The root POM must contain this version and module core:

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.0</version>
    <relativePath/>
</parent>

<groupId>com.ruanzhu</groupId>
<artifactId>doorhandlecatch-parent</artifactId>
<version>0.0.1-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>legacy-service</module>
    <module>platform-common</module>
    <module>platform-security</module>
    <module>event-contracts</module>
    <module>auth-service</module>
    <module>resource-service</module>
    <module>detection-service</module>
    <module>assistant-service</module>
</modules>

<properties>
    <java.version>17</java.version>
    <spring-cloud.version>2025.0.0</spring-cloud.version>
    <spring-cloud-alibaba.version>2025.0.0.0</spring-cloud-alibaba.version>
    <mybatis-plus.version>3.5.17</mybatis-plus.version>
</properties>
```

Import these BOMs in `dependencyManagement` in this order:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>${spring-cloud.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-alibaba-dependencies</artifactId>
    <version>${spring-cloud-alibaba.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-bom</artifactId>
    <version>${mybatis-plus.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: Create the legacy-service POM**

Make `legacy-service/pom.xml` inherit `doorhandlecatch-parent`. Move the existing application dependencies into this POM, remove their explicit Spring-managed versions, and add the MyBatis parser module required by MyBatis-Plus 3.5.9 and later:

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
</dependency>
```

Keep `spring-boot-maven-plugin` in `legacy-service`, not in library modules. Remove the custom `maven-install-plugin` execution and dependency-copy execution from the legacy build.

Create minimal POM files for `platform-common`, `platform-security`, `event-contracts`, `auth-service`, `resource-service`, `detection-service` and `assistant-service` in the same change so the reactor never references a missing module. Each minimal POM inherits the root parent, declares its own `artifactId`, and includes the test starter so later red tests fail for the intended missing behavior rather than missing JUnit:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.ruanzhu</groupId>
        <artifactId>doorhandlecatch-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>platform-common</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Create the remaining files with the same parent and test dependency, using these exact path/artifact pairs:

```text
platform-security/pom.xml  -> platform-security
event-contracts/pom.xml    -> event-contracts
auth-service/pom.xml       -> auth-service
resource-service/pom.xml   -> resource-service
detection-service/pom.xml  -> detection-service
assistant-service/pom.xml  -> assistant-service
```

- [ ] **Step 4: Run the topology test in its new module**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl legacy-service -Dtest=MicroserviceModuleTopologyContractTest test
```

Expected: PASS.

- [ ] **Step 5: Run all legacy tests on the upgraded dependency baseline**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl legacy-service test
```

Expected: PASS. Fix only verified Spring Boot 3.5/MyBatis compatibility errors; do not change business behavior in this task.

- [ ] **Step 6: Commit the reactor conversion**

```powershell
git add pom.xml legacy-service
git commit -m "build: create transitional Maven reactor"
```

---

### Task 4: Add shared modules and event contracts

**Files:**

- Create: `platform-common/**`
- Create: `platform-security/**`
- Create: `event-contracts/**`
- Test: `event-contracts/src/test/java/com/ruanzhu/doorhandlecatch/events/EventEnvelopeTest.java`

- [ ] **Step 1: Write the failing event-envelope test**

```java
package com.ruanzhu.doorhandlecatch.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    @Test
    void requiresIdentityVersionTenantAndAggregate() {
        EventEnvelope event = new EventEnvelope(
                "evt-1", "resource.model.changed", 1,
                100L, "model-9", Instant.parse("2026-07-18T00:00:00Z"),
                Map.of("status", "ACTIVE"));

        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.tenantId()).isEqualTo(100L);
        assertThat(event.aggregateId()).isEqualTo("model-9");
    }
}
```

- [ ] **Step 2: Verify the test fails because EventEnvelope is absent**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl event-contracts -Dtest=EventEnvelopeTest test
```

Expected: compilation failure for missing `EventEnvelope`.

- [ ] **Step 3: Implement the immutable event envelope**

```java
package com.ruanzhu.doorhandlecatch.events;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Long tenantId,
        String aggregateId,
        Instant occurredAt,
        Map<String, Object> payload) {

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("eventId 不能为空");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("eventType 不能为空");
        if (eventVersion < 1) throw new IllegalArgumentException("eventVersion 必须大于 0");
        if (tenantId == null) throw new IllegalArgumentException("tenantId 不能为空");
        if (aggregateId == null || aggregateId.isBlank()) throw new IllegalArgumentException("aggregateId 不能为空");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt 不能为空");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
```

Create `ApiResponse`, `ErrorCode`, `TenantPrincipal` and `RequestContext` in their respective shared modules. Keep all three modules free of Spring Boot application classes, Mapper interfaces and database entities.

Use these contracts:

```java
package com.ruanzhu.doorhandlecatch.common;

public record ApiResponse<T>(boolean success, T data, ErrorDetail error, String requestId) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>(true, data, null, requestId);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message, String requestId) {
        return new ApiResponse<>(false, null, new ErrorDetail(code.name(), message), requestId);
    }

    public record ErrorDetail(String code, String message) {}
}
```

```java
package com.ruanzhu.doorhandlecatch.common;

public enum ErrorCode {
    INVALID_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    TOO_MANY_REQUESTS,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
```

```java
package com.ruanzhu.doorhandlecatch.security;

import java.util.Set;

public record TenantPrincipal(Long userId, Long tenantId, String username, Set<String> roles) {
    public TenantPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
```

```java
package com.ruanzhu.doorhandlecatch.common;

public final class RequestContext {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {}

    public static void setRequestId(String requestId) { REQUEST_ID.set(requestId); }
    public static String requestId() { return REQUEST_ID.get(); }
    public static void clear() { REQUEST_ID.remove(); }
}
```

- [ ] **Step 4: Run shared-module tests**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl platform-common,platform-security,event-contracts test
```

Expected: PASS.

- [ ] **Step 5: Commit shared contracts**

```powershell
git add platform-common platform-security event-contracts
git commit -m "feat: add shared platform and event contracts"
```

---

### Task 5: Create four Nacos/Sentinel-enabled service skeletons

**Files:**

- Create: `auth-service/**`
- Create: `resource-service/**`
- Create: `detection-service/**`
- Create: `assistant-service/**`
- Test: one `ApplicationMetadataTest` in each service

- [ ] **Step 1: Write a failing metadata test in each service**

Use the auth service as the template, changing class and expected name for each module:

```java
class AuthServiceApplicationMetadataTest {

    @Test
    void applicationHasExpectedServiceName() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("name: auth-service");
        assertThat(yaml).contains("optional:nacos:${spring.application.name}.yaml");
    }
}
```

- [ ] **Step 2: Verify the four tests fail because skeleton files do not exist**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl auth-service,resource-service,detection-service,assistant-service test
```

Expected: FAIL for missing service application/configuration files.

- [ ] **Step 3: Add service dependencies**

Each service POM includes:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

Do not add Gateway or OpenFeign dependencies.

- [ ] **Step 4: Add each service application class**

Example:

```java
@SpringBootApplication
@EnableDiscoveryClient
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Add local application.yml files**

Example for auth-service:

```yaml
server:
  port: ${AUTH_SERVICE_PORT:8101}

spring:
  application:
    name: auth-service
  config:
    import:
      - optional:nacos:${spring.application.name}.yaml?refreshEnabled=true
  cloud:
    nacos:
      server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
      username: ${NACOS_USERNAME:nacos}
      password: ${NACOS_PASSWORD:nacos}
      discovery:
        namespace: ${NACOS_NAMESPACE:public}
      config:
        namespace: ${NACOS_NAMESPACE:public}
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:127.0.0.1:8858}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
```

Use ports 8102, 8103 and 8104 for the other services.

- [ ] **Step 6: Run skeleton tests and package all four JARs**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl auth-service,resource-service,detection-service,assistant-service test package
```

Expected: PASS and one executable JAR under each module's `target` directory.

- [ ] **Step 7: Commit the skeletons**

```powershell
git add auth-service resource-service detection-service assistant-service
git commit -m "feat: add Nacos and Sentinel service skeletons"
```

---

### Task 6: Add Nacos, Sentinel, Seata, MySQL, Kafka and Redis infrastructure

**Files:**

- Create: `deploy/distributed/compose.yml`
- Create: `deploy/distributed/.env.example`
- Create: `deploy/distributed/mysql-init/00-create-databases.sh`
- Create: `deploy/distributed/mysql-init/01-seata-server.sql`
- Create: `deploy/distributed/sentinel/Dockerfile`
- Create: `deploy/distributed/seata/application.yml`
- Create: `legacy-service/src/test/java/com/ruanzhu/doorhandlecatch/config/DistributedComposeContractTest.java`

- [ ] **Step 1: Write the failing Compose contract test**

```java
@Test
void composePinsAlibabaMiddlewareAndRequiredInfrastructure() throws Exception {
    Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
    String compose = Files.readString(root.resolve("deploy/distributed/compose.yml"));

    assertThat(compose).contains("nacos/nacos-server:v3.0.3");
    assertThat(compose).contains("apache/seata-server:2.5.0.jdk21");
    assertThat(compose).contains("sentinel-dashboard");
    assertThat(compose).contains("mysql:8.4");
    assertThat(compose).contains("apache/kafka:");
    assertThat(compose).contains("redis:7.4");
    assertThat(compose).doesNotContain("gateway");
}
```

- [ ] **Step 2: Verify the contract fails**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl legacy-service -Dtest=DistributedComposeContractTest test
```

Expected: FAIL because `deploy/distributed/compose.yml` is absent.

- [ ] **Step 3: Create pinned infrastructure definitions**

Use these fixed images:

```yaml
mysql: mysql:8.4
nacos: nacos/nacos-server:v3.0.3
seata-server: apache/seata-server:2.5.0.jdk21
kafka: apache/kafka:3.8.1
redis: redis:7.4-alpine
```

Expose:

```text
MySQL             3306
Nacos client      8848
Nacos gRPC        9848
Nacos console     8088 -> container 8080
Sentinel dashboard 8858
Seata console     7091
Seata transaction 8091
Kafka             9092
Redis             6379
```

Every service must have a health check. Nacos runs in authenticated standalone mode with token and identity values loaded from `deploy/distributed/.env`. Seata uses `store.mode=db`, registers with Nacos, and writes coordinator state into `seata_server`.

- [ ] **Step 4: Create database initialization**

The initialization script creates:

```sql
CREATE DATABASE IF NOT EXISTS door_auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_resource CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_detection CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_assistant CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seata_server CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Import the Seata 2.5.0 MySQL coordinator schema from this pinned official source into `01-seata-server.sql`, preserving `global_table`, `branch_table`, `lock_table`, `distributed_lock` and `vgroup_table` exactly:

```text
https://raw.githubusercontent.com/apache/incubator-seata/v2.5.0/script/server/db/mysql.sql
```

- [ ] **Step 5: Build Sentinel Dashboard from the official release artifact**

`deploy/distributed/sentinel/Dockerfile`:

```dockerfile
FROM eclipse-temurin:17-jre
ARG SENTINEL_VERSION=1.8.9
ADD https://github.com/alibaba/Sentinel/releases/download/${SENTINEL_VERSION}/sentinel-dashboard-${SENTINEL_VERSION}.jar /opt/sentinel-dashboard.jar
EXPOSE 8858
ENTRYPOINT ["java", "-Dserver.port=8858", "-Dcsp.sentinel.dashboard.server=localhost:8858", "-Dproject.name=sentinel-dashboard", "-jar", "/opt/sentinel-dashboard.jar"]
```

- [ ] **Step 6: Validate and start infrastructure**

```powershell
Copy-Item deploy/distributed/.env.example deploy/distributed/.env
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml config
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml up -d --build
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml ps
```

Expected: `config` succeeds and all six containers become healthy.

- [ ] **Step 7: Re-run the contract test**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl legacy-service -Dtest=DistributedComposeContractTest test
```

Expected: PASS.

- [ ] **Step 8: Commit infrastructure**

```powershell
git add deploy/distributed legacy-service/src/test/java/com/ruanzhu/doorhandlecatch/config/DistributedComposeContractTest.java .gitignore
git commit -m "infra: add Alibaba middleware development stack"
```

---

### Task 7: Add startup smoke verification

**Files:**

- Create: `scripts/distributed/smoke.ps1`
- Create: `docs/distributed-development.md`
- Modify: `README.md`

- [ ] **Step 1: Write smoke checks before launching applications**

The script must fail unless all of these endpoints respond:

```text
http://localhost:8088/                         Nacos console
http://localhost:8858/                         Sentinel Dashboard
http://localhost:7091/                         Seata console
http://localhost:8101/actuator/health          auth-service
http://localhost:8102/actuator/health          resource-service
http://localhost:8103/actuator/health          detection-service
http://localhost:8104/actuator/health          assistant-service
```

It must then call the authenticated Nacos service-list API and assert that these names are present:

```text
auth-service
resource-service
detection-service
assistant-service
```

- [ ] **Step 2: Start the four applications**

Run each command in a separate PowerShell terminal:

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl auth-service spring-boot:run
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl resource-service spring-boot:run
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl detection-service spring-boot:run
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl assistant-service spring-boot:run
```

- [ ] **Step 3: Run smoke verification**

```powershell
& scripts/distributed/smoke.ps1
```

Expected: all infrastructure, health endpoints and Nacos registrations report success.

- [ ] **Step 4: Document development startup and shutdown**

`docs/distributed-development.md` must cover prerequisites, environment variables, Docker startup, four service commands, Nacos/Sentinel/Seata console addresses, smoke checks and this shutdown command:

```powershell
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml down
```

- [ ] **Step 5: Commit smoke tooling and documentation**

```powershell
git add scripts/distributed docs/distributed-development.md README.md
git commit -m "docs: add distributed development workflow"
```

---

### Task 8: Run final foundation verification

**Files:** None unless verification reveals a defect covered by this plan.

- [ ] **Step 1: Run the full Maven reactor**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' clean test
```

Expected: all reactor modules finish with `SUCCESS`.

- [ ] **Step 2: Package all JAR applications**

```powershell
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' clean package -DskipTests
```

Expected: executable JARs exist for legacy-service and the four new business services; shared modules produce normal library JARs.

- [ ] **Step 3: Re-run frontend contracts**

```powershell
Set-Location frontend
node --test tests/*.test.cjs
Set-Location ..
```

Expected: PASS; the foundation phase must not change frontend behavior.

- [ ] **Step 4: Validate repository cleanliness**

```powershell
git status --short
git diff --check
```

Expected: no uncommitted generated files, no committed `.env`, logs, database volumes, target files or credentials.

- [ ] **Step 5: Record the phase completion commit**

```powershell
git log --oneline -8
```

Expected: the baseline, reactor, shared-contract, service-skeleton, infrastructure and workflow commits are visible in order.
