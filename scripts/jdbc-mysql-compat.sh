#!/usr/bin/env bash
#
# scripts/jdbc-mysql-compat.sh — JdbcDataStore 真實 MySQL / MariaDB 相容驗證。
#
# 對臨時容器（mysql:8.4、mariadb:11.4）執行 JdbcCompatSuite：
# 新庫（utf8mb4）驗證新形狀建表/長 key/emoji/大值/隔離/重讀，
# 舊庫（latin1 + 舊 DDL 預建表）驗證舊資料載入與升級遷移，
# 同時擷取 SHOW CREATE TABLE 與版本輸出存檔。
#
# 設計紀律：
#   - 容器一律臨時（--rm，結束即清理；trap 保證 stop），不變更 host 服務。
#   - JDBC driver 預設由呼叫端提供（MYSQL_DRIVER_JAR / MARIADB_DRIVER_JAR）；
#     只有顯式傳 --download 才從 Maven Central 拉固定版本。
#   - Gradle test 不依賴此腳本；此腳本只讀 build 產物，不改 repo。
#
# 用法：
#   scripts/jdbc-mysql-compat.sh [--download] [--out <file>]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MYSQL_IMAGE="mysql:8.4"
MARIADB_IMAGE="mariadb:11.4"
MYSQL_DRIVER_URL="https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar"
MARIADB_DRIVER_URL="https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.4.1/mariadb-java-client-3.4.1.jar"
DB_USER="acelib"
DB_PASSWORD="acelibpw"
DB_ROOT_PASSWORD="rootpw"

DOWNLOAD=0
OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --download) DOWNLOAD=1; shift ;;
        --out) OUT="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
if [ -z "$OUT" ]; then
    OUT="/tmp/acelib-jdbc-compat-$(date +%Y%m%d-%H%M%S).log"
fi

MYSQL_JAR="${MYSQL_DRIVER_JAR:-}"
MARIADB_JAR="${MARIADB_DRIVER_JAR:-}"
if [ "$DOWNLOAD" -eq 1 ]; then
    mkdir -p /tmp/acelib-jdbc-drivers
    if [ -z "$MYSQL_JAR" ]; then
        MYSQL_JAR="/tmp/acelib-jdbc-drivers/mysql-connector-j-9.1.0.jar"
        [ -f "$MYSQL_JAR" ] || curl -fsSL -m 120 -o "$MYSQL_JAR" "$MYSQL_DRIVER_URL"
    fi
    if [ -z "$MARIADB_JAR" ]; then
        MARIADB_JAR="/tmp/acelib-jdbc-drivers/mariadb-java-client-3.4.1.jar"
        [ -f "$MARIADB_JAR" ] || curl -fsSL -m 120 -o "$MARIADB_JAR" "$MARIADB_DRIVER_URL"
    fi
fi
if [ -z "$MYSQL_JAR" ] || [ -z "$MARIADB_JAR" ]; then
    echo "need MYSQL_DRIVER_JAR and MARIADB_DRIVER_JAR, or pass --download" >&2
    exit 2
fi

# 編譯測試類別（JdbcCompatMain 住在 test source set）
./gradlew testClasses --console=plain -q
CP="$ROOT/build/classes/java/main:$ROOT/build/classes/java/test:$ROOT/build/resources/main:$MYSQL_JAR:$MARIADB_JAR"

MYSQL_C="acelib-compat-mysql"
MARIA_C="acelib-compat-mariadb"
cleanup() {
    docker stop "$MYSQL_C" >/dev/null 2>&1 || true
    docker stop "$MARIA_C" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_mysql() { # name user pass
    for _ in $(seq 1 40); do
        if docker exec "$1" mysql -u"$2" -p"$3" -e "SELECT 1" >/dev/null 2>&1; then
            return 0
        fi
        sleep 3
    done
    echo "mysql $1 not ready" >&2
    return 1
}

wait_maria() { # name user pass
    for _ in $(seq 1 40); do
        if docker exec "$1" mariadb -u"$2" -p"$3" -e "SELECT 1" >/dev/null 2>&1; then
            return 0
        fi
        sleep 3
    done
    echo "mariadb $1 not ready" >&2
    return 1
}

run_engine() { # container client freshDb label port scheme
    local c="$1" client="$2" fresh="$3" label="$4" port="$5" scheme="$6"
    {
        echo "================================================================"
        echo "engine: $label"
        docker exec "$c" "$client" -uroot -p"$DB_ROOT_PASSWORD" \
            -e "SELECT VERSION(); SHOW VARIABLES LIKE 'character_set_server';"
        echo "--- fresh schema charset (expect utf8mb4) ---"
        docker exec "$c" "$client" -u"$DB_USER" -p"$DB_PASSWORD" "$fresh" \
            -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$fresh';"
        echo "--- JdbcCompatSuite ---"
        # shellcheck disable=SC2086
        java -cp "$CP" com.smile.acelib.data.JdbcCompatMain \
            "$scheme://127.0.0.1:$port/$fresh?useSSL=false&allowPublicKeyRetrieval=true" \
            "$scheme://127.0.0.1:$port/acelib_legacy?useSSL=false&allowPublicKeyRetrieval=true" \
            "$DB_USER" "$DB_PASSWORD" "$label" || true
        echo "--- SHOW CREATE TABLE after suite (fresh db) ---"
        docker exec "$c" "$client" -u"$DB_USER" -p"$DB_PASSWORD" "$fresh" \
            -e "SHOW CREATE TABLE acelib_data_kv;"
        echo "--- SHOW CREATE TABLE after suite (legacy db, upgraded) ---"
        docker exec "$c" "$client" -u"$DB_USER" -p"$DB_PASSWORD" "acelib_legacy" \
            -e "SHOW CREATE TABLE acelib_data_kv;"
    } 2>&1 | grep -v "Using a password on the command line"
}

echo "compat evidence log: $OUT"
{
    echo "compat-run at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "mysql image: $(docker image inspect "$MYSQL_IMAGE" --format '{{.RepoDigests}}')"
    echo "mariadb image: $(docker image inspect "$MARIADB_IMAGE" --format '{{.RepoDigests}}')"

    docker run -d --rm --name "$MYSQL_C" \
        -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
        -e MYSQL_DATABASE=acelib -e MYSQL_USER="$DB_USER" -e MYSQL_PASSWORD="$DB_PASSWORD" \
        -p 13307:3306 "$MYSQL_IMAGE" >/dev/null
    docker run -d --rm --name "$MARIA_C" \
        -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
        -e MYSQL_DATABASE=acelib -e MYSQL_USER="$DB_USER" -e MYSQL_PASSWORD="$DB_PASSWORD" \
        -p 13308:3306 "$MARIADB_IMAGE" >/dev/null
    wait_mysql "$MYSQL_C" root "$DB_ROOT_PASSWORD"
    wait_maria "$MARIA_C" root "$DB_ROOT_PASSWORD"

    for c in "$MYSQL_C" "$MARIA_C"; do
        if [ "$c" = "$MYSQL_C" ]; then client="mysql"; else client="mariadb"; fi
        docker exec "$c" "$client" -uroot -p"$DB_ROOT_PASSWORD" \
            -e "CREATE DATABASE IF NOT EXISTS acelib_legacy CHARACTER SET latin1; GRANT ALL PRIVILEGES ON acelib_legacy.* TO '$DB_USER'@'%'; FLUSH PRIVILEGES;" \
            >/dev/null 2>&1
    done

    run_engine "$MYSQL_C" mysql acelib "mysql:8.4" 13307 "jdbc:mysql"
    run_engine "$MARIA_C" mariadb acelib "mariadb:11.4" 13308 "jdbc:mariadb"
    echo "compat-run done"
} | tee "$OUT"

fails="$(grep -c "^\[FAIL\]" "$OUT" || true)"
echo "evidence saved: $OUT (failures=$fails)"
[ "$fails" -eq 0 ]
