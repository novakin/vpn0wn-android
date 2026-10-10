#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
native_build_dir="${BUILDDIR:-$PWD/build}"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}"
make "$native_build_dir/go-1.24.3/.prepared" BUILDDIR="$native_build_dir" GRADLE_USER_HOME="$gradle_cache"
export PATH="$native_build_dir/go-1.24.3/bin:$PATH"
go run ./cmd/wireguard-overlay "$native_build_dir/test-overlay" host
# Go 1.24.3's module index omits added overlay test files.
GODEBUG=goindex=0 go test -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s -run '^TestTunnl' golang.zx2c4.com/wireguard/conn
GODEBUG=goindex=0 go test -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s -run '^TestTunnlHandshake' golang.zx2c4.com/wireguard/device
go test -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s ./uapiserver ./androidbind ./cmd/wireguard-overlay
go test -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s tunnelhandles.go tunnelhandles_test.go
BUILDDIR="$native_build_dir" bash tests/test-context.sh
