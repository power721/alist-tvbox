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
  local docker_args="$TEST_TMP_DIR/docker-args.log"

  docker() {
    printf '%s\n' "$@" > "$docker_args"
  }

  start_container >/dev/null

  assert_file_has_line "${CONFIG[BASE_DIR]}/alist:/opt/alist/data" "$docker_args" "alist volume path containing spaces should remain one docker argument"
  assert_file_lacks_line "$TEST_TMP_DIR/base" "$docker_args" "base path should not be split at spaces"
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

printf 'alist_tvbox tests: PASS\n'
