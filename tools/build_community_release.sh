#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build_dir="${BUILD_DIR:-$project_dir/build-dynamic}"
case "$build_dir" in /*) ;; *) build_dir="$project_dir/$build_dir" ;; esac
output="$build_dir/AI-Passport-Dynamic-Library-3.0.0-merged-0x0.bin"

: "${IDF_PATH:?Source the ESP-IDF export.sh script before running this command}"

idf.py -C "$project_dir" -B "$build_dir" \
    -D SDKCONFIG="$build_dir/sdkconfig" \
    -D SDKCONFIG_DEFAULTS="$project_dir/sdkconfig.defaults" \
    build

python -m esptool --chip esp32c3 merge_bin \
    --output "$output" \
    --flash_mode dio \
    --flash_freq 80m \
    --flash_size 8MB \
    0x0 "$build_dir/bootloader/bootloader.bin" \
    0x8000 "$build_dir/partition_table/partition-table.bin" \
    0x10000 "$build_dir/FoloToy-AI-Passport.bin"

printf '%s\n' "$output"
