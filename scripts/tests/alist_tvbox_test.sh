#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP_DIR"' EXIT

export ALIST_TVBOX_SOURCE_ONLY=1
# shellcheck source=/dev/null
source "$ROOT_DIR/scripts/alist-tvbox.sh"

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf 'ASSERT FAIL: %s\nexpected: [%s]\nactual:   [%s]\n' "$message" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_contains() {
  local needle="$1"
  local haystack="$2"
  local message="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    printf 'ASSERT FAIL: %s\nmissing: [%s]\nin:      [%s]\n' "$message" "$needle" "$haystack" >&2
    exit 1
  fi
}

assert_not_contains() {
  local needle="$1"
  local haystack="$2"
  local message="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    printf 'ASSERT FAIL: %s\nunexpected: [%s]\nin:         [%s]\n' "$message" "$needle" "$haystack" >&2
    exit 1
  fi
}

assert_file_has_line() {
  local expected_line="$1"
  local file="$2"
  local message="$3"
  if ! grep -Fxq "$expected_line" "$file"; then
    printf 'ASSERT FAIL: %s\nmissing line: [%s]\nfile:\n' "$message" "$expected_line" >&2
    cat "$file" >&2
    exit 1
  fi
}

assert_file_lacks_line() {
  local unexpected_line="$1"
  local file="$2"
  local message="$3"
  if grep -Fxq "$unexpected_line" "$file"; then
    printf 'ASSERT FAIL: %s\nunexpected line: [%s]\nfile:\n' "$message" "$unexpected_line" >&2
    cat "$file" >&2
    exit 1
  fi
}

reset_config_for_tests() {
  local key
  for key in "${!DEFAULT_CONFIG[@]}"; do
    CONFIG["$key"]="${DEFAULT_CONFIG[$key]}"
  done
  CONFIG["BASE_DIR"]="$TEST_TMP_DIR/data"
  CONFIG["IMAGE_ID"]="4"
  CONFIG["IMAGE_NAME"]="haroldli/xiaoya-tvbox"
  CONFIG["NETWORK"]="bridge"
  CONFIG["PORT1"]="4567"
  CONFIG["PORT2"]="5344"
  CONFIG["RESTART"]="always"
  CONFIG["MOUNT_WWW"]="false"
  CONFIG["GITHUB_PROXY"]=""
  unset 'CONFIG[DB_TYPE]' 'CONFIG[DB_HOST]' 'CONFIG[DB_PORT]' 'CONFIG[DB_NAME]' 'CONFIG[DB_USER]' 'CONFIG[DB_PASSWORD]' \
        'CONFIG[DB_JDBC_URL]' 'CONFIG[DB_USERNAME]' 'CONFIG[DB_DRIVER]' 'CONFIG[DB_DIALECT]' 'CONFIG[DB_RAW_URL]' \
        'CONFIG[DB_PREV_TYPE]'
}

test_source_only_loads_helpers() {
  type generate_admin_reset_token >/dev/null
  type parse_reset_password_response >/dev/null
  type call_admin_password_reset_api >/dev/null
}

test_parse_reset_password_response() {
  local actual
  actual="$(parse_reset_password_response '{"username":"admin","password":"Abc123!@#xyz"}')"
  assert_eq "Abc123!@#xyz" "$actual" "parse_reset_password_response should extract password"

  actual="$(parse_reset_password_response $'{\n  "username" : "admin",\n  "password" : "3$aqUtTLD2^a"\n}')"
  assert_eq '3$aqUtTLD2^a' "$actual" "parse_reset_password_response should handle pretty JSON"
}

test_generate_admin_reset_token() {
  local token
  token="$(generate_admin_reset_token)"
  assert_eq "32" "${#token}" "generate_admin_reset_token should return 32 characters"
}

test_call_admin_password_reset_api_uses_container_local_endpoint_and_token() {
  local output
  docker() {
    printf '%s\n' "$*"
  }
  output="$(call_admin_password_reset_api "xiaoya-tvbox" "token-123")"
  [[ "$output" == *"exec xiaoya-tvbox sh -lc"* ]] || {
    printf 'ASSERT FAIL: call_admin_password_reset_api should use docker exec\n' >&2
    exit 1
  }
  [[ "$output" == *"X-ADMIN-RESET-TOKEN: token-123"* ]] || {
    printf 'ASSERT FAIL: call_admin_password_reset_api should send reset token header\n' >&2
    exit 1
  }
  [[ "$output" == *"curl -fsS -X POST -H 'X-ADMIN-RESET-TOKEN: token-123' http://127.0.0.1:4567/api/local/admin/password"* ]] || {
    printf 'ASSERT FAIL: call_admin_password_reset_api should call the container-local endpoint\n' >&2
    exit 1
  }
}

test_recreate_stopped_container_recreates_and_preserves_stopped_state() {
  reset_config_for_tests
  unset -f docker 2>/dev/null || true
  local docker_log="$TEST_TMP_DIR/recreate-stopped.log"

  docker() {
    printf '%s\n' "$*" >> "$docker_log"
    case "$1" in
      ps)
        printf 'xiaoya-tvbox\n'
        ;;
      inspect)
        printf 'false\n'
        ;;
      run)
        printf 'new-container-id\n'
        ;;
    esac
  }

  recreate_container_for_changes >/dev/null

  local log
  log="$(cat "$docker_log")"
  assert_contains "rm -f xiaoya-tvbox" "$log" "stopped container should be removed before recreation"
  assert_contains "run -d" "$log" "stopped container should be recreated"
  assert_contains "stop xiaoya-tvbox" "$log" "recreated stopped container should be stopped again"
}

test_update_removes_existing_stopped_container_before_run() {
  reset_config_for_tests
  unset -f docker 2>/dev/null || true
  local docker_log="$TEST_TMP_DIR/update-stopped.log"
  local image_calls_file="$TEST_TMP_DIR/update-image-calls"
  printf '0\n' > "$image_calls_file"

  docker() {
    printf '%s\n' "$*" >> "$docker_log"
    case "$1" in
      images)
        local image_calls
        image_calls="$(cat "$image_calls_file")"
        image_calls=$((image_calls + 1))
        printf '%s\n' "$image_calls" > "$image_calls_file"
        if [[ "$image_calls" -eq 1 ]]; then
          printf 'old-image\n'
        else
          printf 'new-image\n'
        fi
        ;;
      ps)
        if [[ "$*" == *" -a "* ]]; then
          printf 'xiaoya-tvbox\n'
        fi
        ;;
      pull|rm|run)
        return 0
        ;;
    esac
  }

  check_update "-y" >/dev/null

  local log
  log="$(cat "$docker_log")"
  assert_contains "rm -f xiaoya-tvbox" "$log" "update should remove an existing stopped container before docker run"
  assert_contains "run -d" "$log" "update should start a new container after image refresh"
}

test_host_access_info_uses_host_mode_ports() {
  reset_config_for_tests
  CONFIG["NETWORK"]="host"
  CONFIG["PORT1"]="9999"
  CONFIG["PORT2"]="8888"

  get_host_ip() {
    printf '10.0.0.2\n'
  }

  local output
  output="$(show_access_info false)"

  assert_contains "http://10.0.0.2:4567/" "$output" "host mode management URL should use fixed host port"
  assert_contains "http://10.0.0.2:5234/" "$output" "host mode AList URL should use fixed AList port"
  assert_contains "http://10.0.0.2:5678/" "$output" "host mode nginx URL should be shown"
  assert_not_contains "http://10.0.0.2:8888/" "$output" "host mode should not show bridge AList port"
}

test_load_config_imports_opposite_existing_container() {
  reset_config_for_tests
  unset -f docker 2>/dev/null || true
  check_nas_environment() {
    return 1
  }
  CONFIG_FILE="$TEST_TMP_DIR/config/app.conf"
  local mount_path="$TEST_TMP_DIR/opposite-data"

  docker() {
    case "$1" in
      ps)
        printf 'alist-tvbox\n'
        ;;
      inspect)
        case "$*" in
          *".Config.Image"*)
            printf 'haroldli/alist-tvbox\n'
            ;;
          *".HostConfig.NetworkMode"*)
            printf 'bridge\n'
            ;;
          *"4567/tcp"*)
            printf '14567\n'
            ;;
          *"5244/tcp"*)
            printf '15344\n'
            ;;
          *".HostConfig.RestartPolicy.Name"*)
            printf 'unless-stopped\n'
            ;;
          *".Mounts"*)
            printf '%s\n' "$mount_path"
            ;;
        esac
        ;;
    esac
  }

  load_config <<<"x" >/dev/null

  assert_eq "haroldli/alist-tvbox" "${CONFIG[IMAGE_NAME]}" "load_config should import opposite existing container image"
  assert_eq "1" "${CONFIG[IMAGE_ID]}" "load_config should derive image id from imported image"
  assert_eq "14567" "${CONFIG[PORT1]}" "load_config should import management port"
  assert_eq "15344" "${CONFIG[PORT2]}" "load_config should import alist-tvbox AList port from 5244/tcp"
  assert_eq "$mount_path" "${CONFIG[BASE_DIR]}" "load_config should import data mount path"
}

test_blank_port_input_keeps_existing_value() {
  reset_config_for_tests
  clear() {
    :
  }
  recreate_container_for_changes() {
    printf 'unexpected recreate\n'
    exit 1
  }

  local output
  output="$(printf '2\n\n0\n' | show_config_menu)"

  assert_eq "4567" "${CONFIG[PORT1]}" "blank management port input should keep existing value"
  assert_not_contains "端口号必须是数字" "$output" "blank port input should not be treated as invalid"
}

test_start_container_preserves_volume_paths_with_spaces() {
  reset_config_for_tests
  unset -f docker 2>/dev/null || true
  CONFIG["IMAGE_NAME"]="haroldli/alist-tvbox"
  CONFIG["BASE_DIR"]="$TEST_TMP_DIR/base dir"
  CONFIG["DB_TYPE"]="mysql"
  CONFIG["DB_RAW_URL"]="jdbc:mysql://192.168.50.60:3306/atv"
  CONFIG["DB_USER"]="atv"
  CONFIG["DB_PASSWORD"]="secret"
  local docker_args="$TEST_TMP_DIR/docker-args.log"

  docker() {
    printf '%s\n' "$@" > "$docker_args"
  }

  write_db_config_file
  start_container >/dev/null

  assert_file_has_line "${CONFIG[BASE_DIR]}/alist:/opt/alist/data" "$docker_args" "alist volume path containing spaces should remain one docker argument"
  assert_file_lacks_line "$TEST_TMP_DIR/base" "$docker_args" "base path should not be split at spaces"
  assert_file_has_line "TZ=Asia/Shanghai" "$docker_args" "container should use PostgreSQL-compatible timezone"
  assert_not_contains "/opt/atv/config/application.yaml" "$(cat "$docker_args")" "database config should be loaded from /data/atv/config without a separate docker mount"
}

test_external_db_config_disables_sql_init() {
  reset_config_for_tests
  CONFIG["DB_TYPE"]="mysql"
  CONFIG["DB_RAW_URL"]="jdbc:mysql://192.168.50.60:3306/atv"
  CONFIG["DB_USER"]="atv"
  CONFIG["DB_PASSWORD"]="secret"

  write_db_config_file

  local db_config
  db_config="$(db_config_file)"
  assert_file_has_line "  sql:" "$db_config" "external database config should override Spring SQL init"
  assert_file_has_line "    init:" "$db_config" "external database config should override Spring SQL init"
  assert_file_has_line "      mode: never" "$db_config" "external database config must not run H2 data.sql on MySQL"
  assert_file_has_line "    database-platform: org.hibernate.dialect.MySQLDialect" "$db_config" "generated MySQL config should use non-deprecated MySQL dialect"
  assert_file_has_line "    database-platform: org.hibernate.dialect.MySQLDialect" "$ROOT_DIR/src/main/resources/application-mysql.yaml" "MySQL profile should use non-deprecated MySQL dialect"
  assert_not_contains "MySQL8Dialect" "$(cat "$ROOT_DIR/src/main/resources/application-mysql.yaml")" "MySQL profile should not use deprecated MySQL8Dialect"
}

test_prompt_db_connection_defaults_to_host_ip() {
  reset_config_for_tests
  local original_get_host_ip
  original_get_host_ip="$(declare -f get_host_ip)"

  get_host_ip() {
    printf '192.168.50.60\n'
  }

  prompt_db_connection postgresql >/dev/null <<< $'\n\n\n\nsecret\n'

  assert_eq "192.168.50.60" "${CONFIG[DB_HOST]}" "interactive database host default should be host IP"
  assert_eq "5432" "${CONFIG[DB_PORT]}" "PostgreSQL default port should be preserved"
  assert_eq "alist_tvbox" "${CONFIG[DB_NAME]}" "PostgreSQL default database should be preserved"

  eval "$original_get_host_ip"
}

test_postgresql_jdbc_url_sets_timezone() {
  local url
  url="$(build_jdbc_url postgresql 192.168.50.60 5432 atv)"

  assert_eq "jdbc:postgresql://192.168.50.60:5432/atv?options=-c%20TimeZone=Asia/Shanghai" "$url" "PostgreSQL JDBC URL should set a server-compatible timezone"
}

test_entrypoints_set_jvm_timezone_for_postgresql() {
  assert_contains "-Duser.timezone=Asia/Shanghai" "$(cat "$ROOT_DIR/docker/scripts/entrypoint.sh")" "JVM entrypoint should not let PostgreSQL JDBC send PRC timezone"
  assert_contains "-Duser.timezone=Asia/Shanghai" "$(cat "$ROOT_DIR/docker/scripts/entrypoint-native.sh")" "native entrypoint should not let PostgreSQL JDBC send PRC timezone"
}

test_headless_postgresql_url_adds_timezone_when_missing() {
  reset_config_for_tests

  test_db_full() {
    return 0
  }

  do_migration_steps() {
    write_db_config_file
  }

  migrate_db_headless migrate-db \
    --jdbc-url jdbc:postgresql://192.168.50.60:5432/atv \
    --username atv \
    --password secret >/dev/null

  local db_config
  db_config="$(db_config_file)"
  assert_file_has_line "    jdbc-url: jdbc:postgresql://192.168.50.60:5432/atv?options=-c%20TimeZone=Asia/Shanghai" "$db_config" "headless PostgreSQL URL should add timezone option"
  assert_not_contains "database-platform" "$(cat "$db_config")" "generated PostgreSQL config should let Hibernate auto-detect dialect"
  assert_not_contains "PostgreSQLDialect" "$(cat "$ROOT_DIR/src/main/resources/application-postgresql.yaml")" "PostgreSQL profile should let Hibernate auto-detect dialect"
}

test_config_db_apply_refreshes_external_db_config() {
  reset_config_for_tests
  unset -f docker 2>/dev/null || true
  CONFIG["DB_TYPE"]="mysql"
  CONFIG["DB_RAW_URL"]="jdbc:mysql://192.168.50.60:3306/atv"
  CONFIG["DB_USER"]="atv"
  CONFIG["DB_PASSWORD"]="secret"
  mkdir -p "${CONFIG[BASE_DIR]}/db"
  printf 'spring:\n  datasource:\n    jdbc-url: stale\n' > "${CONFIG[BASE_DIR]}/db/application.yaml"

  docker() {
    :
  }
  start_container() {
    :
  }
  show_access_info() {
    :
  }

  config_db_apply >/dev/null

  local db_config
  db_config="$(db_config_file)"
  assert_file_has_line "      mode: never" "$db_config" "config-db apply should migrate and refresh stale external database config"
  [[ ! -f "${CONFIG[BASE_DIR]}/db/application.yaml" ]] || {
    printf 'ASSERT FAIL: legacy database config should be moved to /atv/config\n' >&2
    exit 1
  }
}

test_external_db_profiles_disable_sql_init() {
  local profile
  for profile in mysql postgresql; do
    assert_file_has_line "      mode: never" "$ROOT_DIR/src/main/resources/application-${profile}.yaml" "${profile} profile should not run H2 data.sql"
  done
}

test_migrate_wizard_requires_explicit_target_choice() {
  reset_config_for_tests
  clear() {
    :
  }

  local output
  output="$(printf '\nx' | migrate_wizard)"

  assert_contains "无效选择" "$output" "blank migrate target should be rejected"
  assert_not_contains "PostgreSQL（默认）" "$output" "migrate wizard should not advertise a default database target"
}

test_migrate_db_without_args_opens_wizard() {
  reset_config_for_tests
  local output

  migrate_wizard() {
    printf 'wizard-called\n'
  }

  output="$(dispatch_migrate_db migrate-db)"
  assert_contains "wizard-called" "$output" "migrate-db without arguments should open migration wizard"
}

test_headless_migrate_db_writes_external_db_config_without_mysql_profile() {
  reset_config_for_tests

  test_db_full() {
    return 0
  }

  do_migration_steps() {
    write_db_config_file
  }

  migrate_db_headless migrate-db \
    --jdbc-url jdbc:mysql://192.168.50.60:3306/atv \
    --username atv \
    --password b24ee077 >/dev/null

  local db_config
  db_config="$(db_config_file)"
  assert_file_has_line "    jdbc-url: jdbc:mysql://192.168.50.60:3306/atv" "$db_config" "headless migrate-db should write the provided JDBC URL"
  assert_file_has_line "      mode: never" "$db_config" "headless migrate-db must disable SQL init without relying on mysql profile"
}

test_headless_migrate_db_supports_h2_target() {
  reset_config_for_tests
  CONFIG_FILE="$TEST_TMP_DIR/config/h2-target.conf"

  do_migration_steps() {
    assert_eq "h2" "$1" "headless migrate-db --type h2 should target h2"
    printf 'yes\n' > "$TEST_TMP_DIR/h2-called"
  }

  migrate_db_headless migrate-db --type h2 >/dev/null

  assert_file_has_line "yes" "$TEST_TMP_DIR/h2-called" "headless h2 migration should run migration steps"
}

test_h2_migration_clears_external_config_and_backs_up_existing_h2() {
  reset_config_for_tests
  CONFIG_FILE="$TEST_TMP_DIR/config/h2-migration.conf"
  CONFIG["DB_TYPE"]="mysql"
  CONFIG["DB_RAW_URL"]="jdbc:mysql://192.168.50.60:3306/atv"
  CONFIG["DB_USER"]="atv"
  CONFIG["DB_PASSWORD"]="secret"
  write_db_config_file
  printf 'old-h2\n' > "${CONFIG[BASE_DIR]}/atv.mv.db"
  printf 'trace\n' > "${CONFIG[BASE_DIR]}/atv.trace.db"

  migrate_db_export() {
    printf 'zip\n' > "$2"
  }
  config_db_apply() {
    [[ ! -f "$(db_config_file)" ]] || {
      printf 'ASSERT FAIL: h2 migration should remove external database override before rebuild\n' >&2
      exit 1
    }
  }
  migrate_db_import() {
    [[ -f "$2" ]]
  }

  do_migration_steps h2 >/dev/null

  [[ ! -f "${CONFIG[BASE_DIR]}/atv.mv.db" ]] || {
    printf 'ASSERT FAIL: old H2 mv.db should be moved away before H2 rebuild\n' >&2
    exit 1
  }
  [[ ! -f "${CONFIG[BASE_DIR]}/atv.trace.db" ]] || {
    printf 'ASSERT FAIL: old H2 trace.db should be moved away before H2 rebuild\n' >&2
    exit 1
  }
  find "${CONFIG[BASE_DIR]}/backup" -name atv.mv.db -print -quit | grep -q atv.mv.db || {
    printf 'ASSERT FAIL: old H2 mv.db should be backed up before migration\n' >&2
    exit 1
  }
}

test_source_only_loads_helpers
test_parse_reset_password_response
test_generate_admin_reset_token
test_call_admin_password_reset_api_uses_container_local_endpoint_and_token
test_recreate_stopped_container_recreates_and_preserves_stopped_state
test_update_removes_existing_stopped_container_before_run
test_host_access_info_uses_host_mode_ports
test_load_config_imports_opposite_existing_container
test_blank_port_input_keeps_existing_value
test_start_container_preserves_volume_paths_with_spaces
test_external_db_config_disables_sql_init
test_prompt_db_connection_defaults_to_host_ip
test_postgresql_jdbc_url_sets_timezone
test_entrypoints_set_jvm_timezone_for_postgresql
test_config_db_apply_refreshes_external_db_config
test_external_db_profiles_disable_sql_init
test_migrate_wizard_requires_explicit_target_choice
test_migrate_db_without_args_opens_wizard
test_h2_migration_clears_external_config_and_backs_up_existing_h2
test_headless_postgresql_url_adds_timezone_when_missing
test_headless_migrate_db_writes_external_db_config_without_mysql_profile
test_headless_migrate_db_supports_h2_target

printf 'alist_tvbox tests: PASS\n'
