#!/usr/bin/env bash

set -euo pipefail

extractor_dir="${1:-external/NewPipeExtractor}"
build_file="$extractor_dir/build.gradle"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$script_dir/../patches/pipepipe-extractor-bilibili-safety.patch"

sed -i 's/JavaLanguageVersion\.of(25)/JavaLanguageVersion.of(21)/' "$build_file"
if ! grep -q 'options.release = 21' "$build_file"; then
    sed -i "/options.encoding = 'UTF-8'/a\\        options.release = 21" "$build_file"
fi

if git -C "$extractor_dir" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
    echo "PipePipeExtractor Bilibili safety patch is already applied"
else
    git -C "$extractor_dir" apply --check "$patch_file"
    git -C "$extractor_dir" apply "$patch_file"
    echo "Applied PipePipeExtractor Bilibili safety patch"
fi

grep -n 'JavaLanguageVersion.of(21)' "$build_file"
grep -n 'options.release = 21' "$build_file"
