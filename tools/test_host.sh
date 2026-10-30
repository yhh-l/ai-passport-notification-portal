#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
${CC:-cc} -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I components/play_store/include components/play_store/play_store.c tests/test_play_store.c \
  -o "$test_dir/allocator"
"$test_dir/allocator"
${CC:-cc} -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I tests/stubs -I main -I components/play_store/include \
  main/app_firmware.c components/play_store/play_store.c bootloader_components/play_boot/hooks.c \
  tests/test_firmware_lifecycle.c -o "$test_dir/lifecycle"
"$test_dir/lifecycle"
