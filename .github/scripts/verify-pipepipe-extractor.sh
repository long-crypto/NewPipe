#!/usr/bin/env bash

set -euo pipefail

extractor_dir="${1:-external/NewPipeExtractor}"
build_file="$extractor_dir/build.gradle"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$script_dir/../patches/pipepipe-extractor-bilibili-safety.patch"

grep -q 'JavaLanguageVersion.of(21)' "$build_file"
grep -q 'options.release = 21' "$build_file"
git -C "$extractor_dir" apply --reverse --check "$patch_file"
