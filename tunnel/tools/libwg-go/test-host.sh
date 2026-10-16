#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
native_build_dir="${BUILDDIR:-$PWD/build}"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}"
make "$native_build_dir/go-1.26.9/.prepared" BUILDDIR="$native_build_dir" GRADLE_USER_HOME="$gradle_cache"
export PATH="$native_build_dir/go-1.26.9/bin:$PATH"
go run ./cmd/wireguard-overlay "$native_build_dir/test-overlay" host
go test -modfile="$native_build_dir/test-overlay/go.mod" -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s -run '^TestVpn0wn' golang.zx2c4.com/wireguard/conn
go test -modfile="$native_build_dir/test-overlay/go.mod" -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s -run '^TestVpn0wnHandshake' golang.zx2c4.com/wireguard/device
go test -modfile="$native_build_dir/test-overlay/go.mod" -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s ./uapiserver ./androidbind ./cmd/wireguard-overlay
go test -modfile="$native_build_dir/test-overlay/go.mod" -overlay="$native_build_dir/test-overlay/overlay.json" -race -count=1 -timeout=90s tunnelhandles.go tunnelhandles_test.go
BUILDDIR="$native_build_dir" bash tests/test-context.sh
