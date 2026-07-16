#!/usr/bin/env bash

set -euo pipefail

extractor_dir="${1:-external/NewPipeExtractor}"
build_file="$extractor_dir/build.gradle"

sed -i 's/JavaLanguageVersion\.of(25)/JavaLanguageVersion.of(21)/' "$build_file"
if ! grep -q 'options.release = 21' "$build_file"; then
    sed -i "/options.encoding = 'UTF-8'/a\\        options.release = 21" "$build_file"
fi

grep -n 'JavaLanguageVersion.of(21)' "$build_file"
grep -n 'options.release = 21' "$build_file"
