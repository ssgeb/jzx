# Nginx Docker Compose Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 Docker Compose 构建 Nginx Web 网关和两个 Spring Boot 实例，实现 Vue 静态资源托管、API 反向代理、最少连接负载均衡及智能助手 SSE 流式代理。

**Architecture:** 后端使用同一个多阶段 Dockerfile 构建镜像，Compose 启动 `backend-1` 与 `backend-2`。前端由 Node 阶段构建并复制到 Nginx 镜像，Nginx 作为唯一对外入口，将 `/api` 请求按 `least_conn` 分发至两个后端；MySQL、Redis、Kafka、Mem0 与 OSS 保持外部部署。

**Tech Stack:** Docker Compose、Nginx、Spring Boot、Vue 3、Node Test Runner

---

### Task 1: Backend image and two-instance Compose topology

**Files:**
- Create: `.dockerignore`
- Create: `deploy/backend/Dockerfile`
- Create: `compose.nginx.yml`
- Create: `frontend/tests/nginx-compose-deployment-contract.test.cjs`

- [ ] **Step 1: Write the failing backend topology tests**

Create `frontend/tests/nginx-compose-deployment-contract.test.cjs`:

```javascript
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')
const serviceBlock = (compose, name) => {
  const match = compose.match(new RegExp(`^  ${name}:\\n([\\s\\S]*?)(?=^  [\\w-]+:|^volumes:|^networks:)`, 'm'))
  assert.ok(match, `missing service ${name}`)
  return match[1]
}

test('compose declares two private Spring Boot instances', () => {
  const compose = read('compose.nginx.yml')
  assert.match(compose, /^\s{2}backend-1:/m)
  assert.match(compose, /^\s{2}backend-2:/m)
  assert.doesNotMatch(serviceBlock(compose, 'backend-1'), /ports:/)
  assert.doesNotMatch(serviceBlock(compose, 'backend-2'), /ports:/)
  assert.match(compose, /SERVER_PORT:\s*8080/)
  assert.match(compose, /host\.docker\.internal:host-gateway/)
})

test('backend image uses Maven build and non-root Java runtime', () => {
  const dockerfile = read('deploy/backend/Dockerfile')
  assert.match(dockerfile, /FROM maven:.* AS build/)
  assert.match(dockerfile, /mvnw\.cmd|\.\/mvnw|mvn .*package/)
  assert.match(dockerfile, /FROM eclipse-temurin:17-jre/)
  assert.match(dockerfile, /USER app/)
  assert.match(dockerfile, /actuator\/health/)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: FAIL with `ENOENT` for `compose.nginx.yml`.

- [ ] **Step 3: Add backend Docker build**

Create `.dockerignore`:

```text
.git
.idea
.vscode
target
frontend/node_modules
frontend/dist
node_modules
uploads
logs
*.log
.env
.env.*
```

Create `deploy/backend/Dockerfile`:

```dockerfile
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw mvnw.cmd ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/target/DoorHandleCatch-*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

- [ ] **Step 4: Add initial Compose topology**

Create `compose.nginx.yml`:

```yaml
name: doorhandlecatch

x-backend-service: &backend-service
  build:
    context: .
    dockerfile: deploy/backend/Dockerfile
  env_file:
    - ${DOORHANDLE_ENV_FILE:-deploy/docker.env.example}
  environment:
    SERVER_PORT: 8080
  expose:
    - "8080"
  extra_hosts:
    - "host.docker.internal:host-gateway"
  volumes:
    - doorhandle-runtime:/app/uploads
  networks:
    - doorhandle-network
  restart: unless-stopped

services:
  backend-1:
    <<: *backend-service
  backend-2:
    <<: *backend-service

volumes:
  doorhandle-runtime:

networks:
  doorhandle-network:
    driver: bridge
```

- [ ] **Step 5: Run backend topology tests and verify GREEN**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit backend topology**

```powershell
git add .dockerignore deploy/backend/Dockerfile compose.nginx.yml frontend/tests/nginx-compose-deployment-contract.test.cjs
git commit -m "feat: add containerized backend replicas"
```

### Task 2: Nginx Web server, reverse proxy, load balancing, and SSE

**Files:**
- Create: `deploy/nginx/Dockerfile`
- Create: `deploy/nginx/nginx.conf`
- Modify: `compose.nginx.yml`
- Modify: `frontend/tests/nginx-compose-deployment-contract.test.cjs`

- [ ] **Step 1: Append failing Nginx behavior tests**

Append these tests:

```javascript
test('nginx serves Vue and balances API traffic across both backends', () => {
  const nginx = read('deploy/nginx/nginx.conf')
  assert.match(nginx, /upstream doorhandle_backend\s*{[\s\S]*least_conn;/)
  assert.match(nginx, /server backend-1:8080 max_fails=3 fail_timeout=10s;/)
  assert.match(nginx, /server backend-2:8080 max_fails=3 fail_timeout=10s;/)
  assert.match(nginx, /location \/api\/\s*{[\s\S]*proxy_pass http:\/\/doorhandle_backend;/)
  assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html;/)
  assert.match(nginx, /location \/assets\/\s*{[\s\S]*immutable/)
})

test('nginx keeps assistant SSE responses unbuffered', () => {
  const nginx = read('deploy/nginx/nginx.conf')
  assert.match(nginx, /location = \/api\/chat-assistant\/messages\/stream/)
  assert.match(nginx, /proxy_buffering off;/)
  assert.match(nginx, /proxy_cache off;/)
  assert.match(nginx, /proxy_read_timeout 300s;/)
})

test('compose exposes only nginx to the host', () => {
  const compose = read('compose.nginx.yml')
  assert.match(compose, /^\s{2}nginx:/m)
  assert.match(compose, /NGINX_PORT:-80}:80/)
  assert.match(compose, /condition:\s*service_healthy/)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: the first 2 tests pass and the Nginx tests fail with missing files/configuration.

- [ ] **Step 3: Add the Nginx multi-stage image**

Create `deploy/nginx/Dockerfile`:

```dockerfile
FROM node:20-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/nginx/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O - http://127.0.0.1/nginx-health || exit 1
```

- [ ] **Step 4: Add production Nginx configuration**

Create `deploy/nginx/nginx.conf` with:

```nginx
upstream doorhandle_backend {
    least_conn;
    server backend-1:8080 max_fails=3 fail_timeout=10s;
    server backend-2:8080 max_fails=3 fail_timeout=10s;
    keepalive 32;
}

server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    client_max_body_size 220m;

    gzip on;
    gzip_vary on;
    gzip_types text/plain text/css application/json application/javascript application/xml image/svg+xml;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location = /nginx-health {
        access_log off;
        default_type text/plain;
        return 200 "ok\n";
    }

    location /assets/ {
        try_files $uri =404;
        expires 1y;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }

    location = /api/chat-assistant/messages/stream {
        proxy_pass http://doorhandle_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
        add_header X-Accel-Buffering no;
    }

    location /api/ {
        proxy_pass http://doorhandle_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 5s;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 5: Complete the Nginx Compose service**

Add this service under `services` in `compose.nginx.yml`:

```yaml
  nginx:
    build:
      context: .
      dockerfile: deploy/nginx/Dockerfile
    ports:
      - "${NGINX_PORT:-80}:80"
    depends_on:
      backend-1:
        condition: service_healthy
      backend-2:
        condition: service_healthy
    networks:
      - doorhandle-network
    restart: unless-stopped
```

- [ ] **Step 6: Run Nginx contract tests and verify GREEN**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: 5 tests pass.

- [ ] **Step 7: Commit Nginx gateway**

```powershell
git add deploy/nginx compose.nginx.yml frontend/tests/nginx-compose-deployment-contract.test.cjs
git commit -m "feat: add nginx web gateway and load balancing"
```

### Task 3: External dependency configuration and deployment guide

**Files:**
- Create: `deploy/docker.env.example`
- Create: `docs/nginx-compose-deployment.md`
- Modify: `README.md`
- Modify: `frontend/tests/nginx-compose-deployment-contract.test.cjs`

- [ ] **Step 1: Append failing configuration safety test**

Append:

```javascript
test('deployment environment example uses external services and placeholders', () => {
  const env = read('deploy/docker.env.example')
  assert.match(env, /DB_URL=jdbc:mysql:\/\/host\.docker\.internal:3306\/doorhandledb/)
  assert.match(env, /REDIS_HOST=host\.docker\.internal/)
  assert.match(env, /APP_KAFKA_BOOTSTRAP_SERVERS=host\.docker\.internal:9092/)
  assert.match(env, /JWT_SECRET=replace-with-at-least-32-random-characters/)
  assert.doesNotMatch(env, /sk-[A-Za-z0-9]{16,}/)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: 5 tests pass and the environment test fails with `ENOENT`.

- [ ] **Step 3: Add safe environment example**

Create `deploy/docker.env.example`:

```dotenv
DB_URL=jdbc:mysql://host.docker.internal:3306/doorhandledb?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8mb4
DB_USERNAME=root
DB_PASSWORD=replace-with-database-password
REDIS_HOST=host.docker.internal
REDIS_PORT=6379
REDIS_PASSWORD=replace-with-redis-password
APP_KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092
MEM0_SERVICE_URL=http://host.docker.internal:8081
MEM0_ENABLED=true
JWT_SECRET=replace-with-at-least-32-random-characters
DEEPSEEK_ENABLED=true
DEEPSEEK_API_KEY=replace-with-deepseek-api-key
ALIYUN_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com
ALIYUN_OSS_BUCKET=replace-with-oss-bucket
ALIYUN_ACCESS_KEY_ID=replace-with-oss-access-key-id
ALIYUN_ACCESS_KEY_SECRET=replace-with-oss-access-key-secret
JAVA_OPTS=-Xms512m -Xmx1024m
```

- [ ] **Step 4: Write deployment guide**

Create `docs/nginx-compose-deployment.md` with these exact operational steps:

1. Install/start Docker Desktop and enable Docker Compose V2;
2. copy `deploy/docker.env.example` to `deploy/docker.env`, replace placeholders, and set `DOORHANDLE_ENV_FILE=deploy/docker.env` before starting Compose;
3. ensure external MySQL, Redis, Kafka and optional Mem0 endpoints accept connections from Docker;
4. run `docker compose -f compose.nginx.yml --env-file deploy/docker.env up -d --build`;
5. inspect `docker compose ... ps` and logs;
6. verify `/nginx-health`, frontend routing, repeated API calls, and SSE streaming; inspect backend health through `docker compose ps`;
7. document stop, rebuild and rollback commands.

Update `README.md` with a “Nginx + Docker Compose production deployment” section linking to this guide.

- [ ] **Step 5: Run contract tests and verify GREEN**

Run from `frontend`:

```powershell
node --test tests/nginx-compose-deployment-contract.test.cjs
```

Expected: 6 tests pass.

- [ ] **Step 6: Commit configuration and docs**

```powershell
git add deploy/docker.env.example docs/nginx-compose-deployment.md README.md frontend/tests/nginx-compose-deployment-contract.test.cjs
git commit -m "docs: add nginx compose deployment guide"
```

### Task 4: Verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Validate Nginx deployment contracts**

```powershell
Set-Location frontend
node --test tests/nginx-compose-deployment-contract.test.cjs
node --test tests/*.test.cjs
npm run build
Set-Location ..
```

Expected: all Node contracts pass and Vite production build exits 0.

- [ ] **Step 2: Validate backend and Python regressions**

```powershell
.\mvnw.cmd clean test
.\tests_python\run_test.bat
```

Expected: Java and Python suites exit 0.

- [ ] **Step 3: Validate Compose when tooling is available**

```powershell
docker compose -f compose.nginx.yml --env-file deploy/docker.env.example config
```

Expected when Docker Compose V2 is installed: rendered configuration exits 0. On the current machine, record the known prerequisite blocker (`docker compose` plugin unavailable) without weakening repository contract verification.

- [ ] **Step 4: Check patch hygiene and repository state**

```powershell
git diff --check
git status -sb
```

Expected: no whitespace errors and only intentional branch commits remain.
