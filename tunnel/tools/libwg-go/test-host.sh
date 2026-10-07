#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
native_build_dir="${BUILDDIR:-$PWD/build}"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}"
make "$native_build_dir/go-1.24.3/.prepared" BUILDDIR="$native_build_dir" GRADLE_USER_HOME="$gradle_cache"
export PATH="$native_build_dir/go-1.24.3/bin:$PATH"
go test -count=1 bind-overlay.go bind-overlay_test.go
go run bind-overlay.go "$native_build_dir/descriptor-host-overlay" host
go test -race -count=1 -timeout=90s ./uapiserver
go test -overlay="$native_build_dir/descriptor-host-overlay/overlay.json" -race -count=1 -timeout=90s -run '^TestTunnlAndroidDescriptors' golang.zx2c4.com/wireguard/conn
go test -overlay="$native_build_dir/descriptor-host-overlay/overlay.json" -race -count=1 -timeout=90s tunnelhandles.go tunnelbind.go tunnelhandles_test.go tunnelbind_test.go
