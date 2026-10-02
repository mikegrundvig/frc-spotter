# shellcheck shell=bash
# stamp.sh: what it writes for a computer, and what it refuses.

setup_stamp() {
  need_yq
  make_repo
  make_settings_tool
}

test_team_numbers_split_into_te_and_am() {
  # shellcheck source=../lib/common.sh
  . "$IMAGE/lib/common.sh"
  assert_eq "$(team_prefix 1)" 10.0.1
  assert_eq "$(team_prefix 254)" 10.2.54
  assert_eq "$(team_prefix 1002)" 10.10.2
  assert_eq "$(team_prefix 12345)" 10.123.45
  assert_eq "$(team_prefix 25599)" 10.255.99
}

test_stamp_writes_the_computers_identity() {
  setup_stamp
  run_stamp vision-front
  local root=$TMP/out/root
  assert_eq "$(cat "$root/etc/hostname")" vision-front hostname
  # Every computer in the table, by its robot address.
  assert_contains "$root/etc/hosts" $'10.12.34.11\tvision-front'
  assert_contains "$root/etc/hosts" $'10.12.34.12\tvision-back'
  assert_contains "$root/etc/hosts" $'127.0.0.1\tlocalhost'
  assert_not_contains "$root/etc/hosts" 127.0.1.1
  [[ $(cat "$root/etc/machine-id") =~ ^[0-9a-f]{32}$ ]] || fail "machine-id isn't 32 hex digits"
}

test_stamp_gives_each_computer_its_own_machine_id() {
  setup_stamp
  run_stamp vision-front
  local front
  front=$(cat "$TMP/out/root/etc/machine-id")
  rm -rf "$TMP/out"
  run_stamp vision-back
  [[ $front != "$(cat "$TMP/out/root/etc/machine-id")" ]] || fail "two computers share a machine ID"
}

test_stamp_writes_the_robot_network_profile() {
  setup_stamp
  run_stamp vision-front
  local profile=$TMP/out/root/etc/NetworkManager/system-connections/robot.nmconnection
  assert_file "$profile"
  # NetworkManager ignores a profile others can read.
  assert_mode "$profile" 600
  assert_contains "$profile" 'type=ethernet'
  assert_contains "$profile" 'method=manual'
  # FRC's documented netmask and gateway for on-robot devices.
  assert_contains "$profile" 'address1=10.12.34.11/24,10.12.34.4'
  assert_contains "$profile" 'dad-timeout=3000'
  assert_contains "$profile" 'autoconnect-retries=0'
  grep -Eq '^uuid=[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' "$profile" ||
    fail "the profile's uuid isn't a UUID"
}

test_stamp_writes_the_stamp_twice() {
  setup_stamp
  run_stamp vision-back --stamp-out "$TMP/stamp.json"
  local stamp=$TMP/out/root/etc/coprocessor/stamp.json
  assert_file "$stamp"
  cmp -s "$stamp" "$TMP/out/coproc/stamp.json" || fail "COPROC's copy differs from the root's"
  cmp -s "$stamp" "$TMP/stamp.json" || fail "--stamp-out differs from the root's"
  assert_eq "$(yq -r '.name' "$stamp")" vision-back
  assert_eq "$(yq -r '.address' "$stamp")" 10.12.34.12
  assert_eq "$(yq -r '.team' "$stamp")" 1234
  assert_eq "$(yq -r '.board' "$stamp")" orangepi-5-plus
  assert_eq "$(yq -o=json -I=0 '.cameras' "$stamp")" '["back"]'
  assert_eq "$(yq -r '.agentPort' "$stamp")" 5809 "the computer's own port"
  assert_eq "$(yq -r '.release' "$stamp")" coprocessors-1
  assert_eq "$(yq -r '.photonvisionVersion' "$stamp")" dev-v2027.0.0-alpha-2-69-g71416112
  assert_eq "$(yq -r '.recipeHash' "$stamp")" 2222222222222222222222222222222222222222222222222222222222222222
  assert_eq "$(yq -r '.settingsHash' "$stamp")" "" "vision-back's settings hash (it has none)"
  # The members of the agent's Stamp record, less what the agent adds as it answers.
  assert_eq "$(yq -r 'keys | join(" ")' "$stamp")" \
    "name team address board cameras release recipeHash photonvisionVersion settingsHash agentPort"
  assert_contains "$TMP/out/coproc/README.txt" "vision-back, 10.12.34.12"
}

test_stamp_uses_the_tables_port_unless_the_computer_has_its_own() {
  setup_stamp
  run_stamp vision-front
  assert_eq "$(yq -r '.agentPort' "$TMP/out/root/etc/coprocessor/stamp.json")" 5808
  yq -i 'del(.agentPort)' "$TMP/repo/coprocessors/coprocessors.yaml"
  run_stamp vision-front
  assert_eq "$(yq -r '.agentPort' "$TMP/out/root/etc/coprocessor/stamp.json")" 5808 "the default"
}

test_stamp_lists_no_cameras_as_an_empty_list() {
  setup_stamp
  yq -i 'del(.computers[0].cameras)' "$TMP/repo/coprocessors/coprocessors.yaml"
  run_stamp vision-front
  assert_eq "$(yq -o=json -I=0 '.cameras' "$TMP/out/root/etc/coprocessor/stamp.json")" '[]'
}

test_stamp_builds_the_settings_database_from_the_committed_rows() {
  setup_stamp
  run_stamp vision-front
  local db=$TMP/out/data/photonvision_config/photon.sqlite
  assert_file "$db"
  assert_contains "$db" 'SQLite format 3'
  assert_contains "$db" '"calibration": [1, 2, 3]'
  # The tool got the computer's rows, the empty database, and where to write.
  assert_eq "$(sed -n 1p "$TMP/tool.log")" "$TMP/repo/coprocessors/vision-front/settings"
  assert_eq "$(sed -n 2p "$TMP/tool.log")" "$TMP/empty.sqlite"
  assert_eq "$(sed -n 3p "$TMP/tool.log")" "$db"
  # The stamp carries the tool's hash.
  local expected
  expected=$(sha256sum <"$TMP/repo/coprocessors/vision-front/settings/cameras/front-left.json" | cut -c1-64)
  assert_eq "$(yq -r '.settingsHash' "$TMP/out/root/etc/coprocessor/stamp.json")" "$expected"
}

test_stamp_without_settings_leaves_photonvision_its_defaults() {
  setup_stamp
  run_stamp vision-back
  assert_no_file "$TMP/out/data/photonvision_config/photon.sqlite"
  assert_no_file "$TMP/tool.log"
  # Neither the tool nor the empty database is needed then.
  rm -rf "$TMP/out"
  mkdir -p "$TMP/out/root" "$TMP/out/coproc" "$TMP/out/data"
  "$IMAGE/stamp.sh" --computer vision-back --repo "$TMP/repo" \
    --root "$TMP/out/root" --coproc "$TMP/out/coproc" --data "$TMP/out/data" --release r1 \
    --recipe-hash 2222222222222222222222222222222222222222222222222222222222222222 \
    --photonvision-version v2027.1.0
  assert_eq "$(yq -r '.settingsHash' "$TMP/out/root/etc/coprocessor/stamp.json")" ""
}

test_stamp_needs_the_settings_tool_for_committed_settings() {
  setup_stamp
  mkdir -p "$TMP/out/root" "$TMP/out/coproc" "$TMP/out/data"
  assert_fails "--settings-tool is required" "$IMAGE/stamp.sh" --computer vision-front \
    --repo "$TMP/repo" --root "$TMP/out/root" --coproc "$TMP/out/coproc" --data "$TMP/out/data" \
    --release r1 --recipe-hash 2222222222222222222222222222222222222222222222222222222222222222 \
    --photonvision-version v2027.1.0
}

test_stamp_makes_the_folders_datas_bind_mounts_need() {
  setup_stamp
  run_stamp vision-back
  assert_mode "$TMP/out/data/photonvision_config" 755
  assert_mode "$TMP/out/data/journal" 2755
  assert_mode "$TMP/out/data/ssh" 700
}

test_stamp_installs_the_teams_public_keys_without_their_comments() {
  setup_stamp
  printf '# the team laptop\n\nssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOtherExampleKey mentor@example.org\n' \
    >>"$TMP/repo/coprocessors/authorized_keys"
  run_stamp vision-front
  local keys=$TMP/out/root/home/photon/.ssh/authorized_keys
  assert_eq "$(cat "$keys")" "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTestsOnly
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOtherExampleKey" "authorized_keys"
  assert_mode "$keys" 644
  assert_mode "$TMP/out/root/home/photon/.ssh" 755
}

test_stamp_without_keys_leaves_ssh_closed() {
  setup_stamp
  run_stamp vision-front
  rm "$TMP/repo/coprocessors/authorized_keys"
  run_stamp vision-front
  assert_no_file "$TMP/out/root/home/photon/.ssh/authorized_keys"
}

test_stamp_refuses_a_private_key() {
  setup_stamp
  # Built here, so the test file itself never looks like a key to a secret scanner.
  local kind="PRIV""ATE"
  printf -- '-----BEGIN OPENSSH %s KEY-----\nabc\n-----END OPENSSH %s KEY-----\n' "$kind" "$kind" \
    >"$TMP/repo/coprocessors/authorized_keys"
  assert_fails "private key" run_stamp vision-front
}

test_stamp_refuses_a_line_that_isnt_a_public_key() {
  setup_stamp
  echo 'my password is hunter2' >>"$TMP/repo/coprocessors/authorized_keys"
  assert_fails "isn't an SSH public key" run_stamp vision-front
}

test_stamp_is_repeatable() {
  setup_stamp
  run_stamp vision-front
  local first
  first=$(tree_digest "$TMP/out")
  run_stamp vision-front
  assert_eq "$(tree_digest "$TMP/out")" "$first" "the second stamp's files"
}

test_stamp_refuses_a_computer_not_in_the_table() {
  setup_stamp
  assert_fails "no computer named 'vision-side'" run_stamp vision-side
}

test_stamp_refuses_team_zero() {
  setup_stamp
  yq -i '.team = 0' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "set your team's" run_stamp vision-front
}

test_stamp_refuses_addresses_outside_frcs_range() {
  setup_stamp
  yq -i '.computers[0].address = 5' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "outside FRC's range" run_stamp vision-front
  yq -i '.computers[0].address = 20' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "outside FRC's range" run_stamp vision-front
  yq -i '.computers[0].address = 19' "$TMP/repo/coprocessors/coprocessors.yaml"
  run_stamp vision-front
}

test_stamp_refuses_two_computers_at_one_address() {
  setup_stamp
  yq -i '.computers[1].address = 11' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "two computers have the address 11" run_stamp vision-front
}

test_stamp_refuses_two_computers_with_one_name() {
  setup_stamp
  yq -i '.computers[1].name = "vision-front"' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "two computers are named vision-front" run_stamp vision-front
}

# Numbers are written plainly: bash would read 0254 as octal, and 09 not at all.
test_stamp_refuses_numbers_with_leading_zeros() {
  setup_stamp
  local table=$TMP/repo/coprocessors/coprocessors.yaml
  sed -i 's/^team: 1234$/team: 0254/' "$table"
  assert_fails "team '0254' isn't a team number" run_stamp vision-front
  sed -i 's/^team: 0254$/team: 254/; s/address: 11$/address: 09/' "$table"
  assert_fails "address '09' is outside" run_stamp vision-front
  sed -i 's/address: 09$/address: 9/' "$table"
  run_stamp vision-front
  assert_eq "$(yq -r '.address' "$TMP/out/root/etc/coprocessor/stamp.json")" 10.2.54.9
}

test_stamp_refuses_a_team_number_too_big() {
  setup_stamp
  yq -i '.team = 25600' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "isn't a team number" run_stamp vision-front
}

test_stamp_refuses_a_port_that_isnt_one() {
  setup_stamp
  local table=$TMP/repo/coprocessors/coprocessors.yaml port
  for port in 0 65536 08080 '"http"'; do
    yq -i ".agentPort = $port" "$table"
    assert_fails "agentPort" run_stamp vision-front
  done
  yq -i '.agentPort = 5808 | .computers[1].agentPort = 70000' "$table"
  assert_fails "vision-back's agentPort '70000'" run_stamp vision-front
}

test_stamp_refuses_cameras_that_arent_a_list_of_names() {
  setup_stamp
  local table=$TMP/repo/coprocessors/coprocessors.yaml
  yq -i '.computers[0].cameras = "front-left"' "$table"
  assert_fails "cameras must be a list" run_stamp vision-front
  yq -i '.computers[0].cameras = ["front/left"]' "$table"
  assert_fails "cameras must each be a name" run_stamp vision-front
  yq -i '.computers[0].cameras = [{"name": "front"}]' "$table"
  assert_fails "cameras must each be a name" run_stamp vision-front
  yq -i '.computers[0].cameras = [""]' "$table"
  assert_fails "cameras must each be a name" run_stamp vision-front
}

test_stamp_writes_camera_names_as_text() {
  setup_stamp
  sed -i 's/cameras: \[front-left, front-right\]/cameras: [7, front-right]/' \
    "$TMP/repo/coprocessors/coprocessors.yaml"
  run_stamp vision-front
  assert_eq "$(yq -o=json -I=0 '.cameras' "$TMP/out/root/etc/coprocessor/stamp.json")" '["7","front-right"]'
}

test_stamp_refuses_a_name_that_cant_be_a_hostname() {
  setup_stamp
  yq -i '.computers[1].name = "Vision_Back"' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "can't be a computer's name" run_stamp vision-front
}

test_stamp_refuses_an_unknown_board() {
  setup_stamp
  yq -i '.computers[0].board = "raspberry-pi-5"' "$TMP/repo/coprocessors/coprocessors.yaml"
  assert_fails "isn't one of" run_stamp vision-front
}

test_stamp_fails_when_the_settings_tool_fails() {
  setup_stamp
  printf '#!/bin/sh\necho "no such table: global" >&2\nexit 3\n' >"$TMP/settings-tool"
  assert_fails "settings tool failed" run_stamp vision-front
}

# A JVM can print its own warnings to standard output before the tool's hash.
test_stamp_takes_the_settings_tools_last_line_as_the_hash() {
  setup_stamp
  mv "$TMP/settings-tool" "$TMP/settings-tool-quiet"
  printf '#!/bin/sh\necho "[0.002s][warning][os,container] Cgroup memory controller path moved"\nexec "%s" "$@"\n' \
    "$TMP/settings-tool-quiet" >"$TMP/settings-tool"
  chmod +x "$TMP/settings-tool"
  local output
  output=$(run_stamp vision-front 2>&1)
  [[ $output == *"also said: [0.002s][warning]"* ]] || fail "the extra line wasn't passed on: $output"
  [[ $(yq -r '.settingsHash' "$TMP/out/root/etc/coprocessor/stamp.json") =~ ^[0-9a-f]{64}$ ]] ||
    fail "settingsHash isn't the tool's hash"
}

test_stamp_refuses_a_settings_hash_that_isnt_one() {
  setup_stamp
  # shellcheck disable=SC2016 # the stand-in's own $2 and $3
  printf '#!/bin/sh\ncp "$2" "$3"\necho abc\n' >"$TMP/settings-tool"
  assert_fails "not a sha256" run_stamp vision-front
}

test_stamp_refuses_another_yq() {
  setup_stamp
  mkdir -p "$TMP/bin"
  printf '#!/bin/sh\necho "yq 3.4.3"\n' >"$TMP/bin/yq"
  chmod +x "$TMP/bin/yq"
  PATH=$TMP/bin:$PATH assert_fails "mikefarah's yq" run_stamp vision-front
}
