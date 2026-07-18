#!/usr/bin/env bash
set -e

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<'EOSQL'
CREATE DATABASE IF NOT EXISTS door_auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_resource CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_detection CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS door_assistant CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seata_server CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOSQL
