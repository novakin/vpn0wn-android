# SPDX-License-Identifier: Apache-2.0
# Modified for vpn0wn.
#
# Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.

BUILDDIR ?= $(CURDIR)/build
DESTDIR ?= $(CURDIR)/out

NDK_GO_ARCH_MAP_x86 := 386
NDK_GO_ARCH_MAP_x86_64 := amd64
NDK_GO_ARCH_MAP_arm := arm
NDK_GO_ARCH_MAP_arm64 := arm64
NDK_GO_ARCH_MAP_mips := mipsx
NDK_GO_ARCH_MAP_mips64 := mips64x

comma := ,
CLANG_FLAGS := --target=$(TARGET) --sysroot=$(SYSROOT)
export CGO_CFLAGS := $(CLANG_FLAGS) $(subst -mthumb,-marm,$(CFLAGS))
export CGO_LDFLAGS := $(CLANG_FLAGS) $(patsubst -Wl$(comma)--build-id=%,-Wl$(comma)--build-id=none,$(LDFLAGS)) -Wl,-soname=libwg-go.so
export GOARCH := $(NDK_GO_ARCH_MAP_$(ANDROID_ARCH_NAME))
export GOOS := android
export CGO_ENABLED := 1

GO_VERSION := 1.26.9
GO_PLATFORM := $(shell uname -s | tr '[:upper:]' '[:lower:]')-$(NDK_GO_ARCH_MAP_$(shell uname -m))
GO_TARBALL := go$(GO_VERSION).$(GO_PLATFORM).tar.gz
GO_HASH_darwin-amd64 := 00c29e3d4c8562f547410daa18d2959b641bd691ab00c4a525ea817a0253d4bd
GO_HASH_darwin-arm64 := f9bb7c0a02506c5d9bf0d1eb1f7ee6c7684f844ae49558308a0427b830e022cc
GO_HASH_linux-amd64 := 42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d

default: $(DESTDIR)/libwg-go.so

.PHONY: default force-build
force-build:

$(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL):
	mkdir -p "$(dir $@)"
	flock "$@.lock" -c ' \
	[ -f "$@" ] && exit 0; \
	curl -o "$@.tmp" "https://dl.google.com/go/$(GO_TARBALL)" && \
	echo "$(GO_HASH_$(GO_PLATFORM))  $@.tmp" | sha256sum -c && \
	mv "$@.tmp" "$@"'

$(BUILDDIR)/go-$(GO_VERSION)/.prepared: $(GRADLE_USER_HOME)/caches/golang/$(GO_TARBALL) goruntime-boottime-over-monotonic.diff Makefile
	mkdir -p "$(BUILDDIR)"
	flock "$(BUILDDIR)/go-$(GO_VERSION).lock" -c 'set -e; \
	if [ "$@" -nt "$<" ] && [ "$@" -nt goruntime-boottime-over-monotonic.diff ] && [ "$@" -nt Makefile ]; then exit 0; fi; \
	rm -rf "$(dir $@)"; \
	echo "$(GO_HASH_$(GO_PLATFORM))  $<" | sha256sum -c; \
	mkdir -p "$(dir $@)"; \
	tar -C "$(dir $@)" --strip-components=1 -xzf "$<" && \
	patch -p1 -f -N -r- -d "$(dir $@)" < goruntime-boottime-over-monotonic.diff && \
	touch "$@"'

$(DESTDIR)/libwg-go.so: export PATH := $(BUILDDIR)/go-$(GO_VERSION)/bin/:$(PATH)
$(DESTDIR)/libwg-go.so: $(BUILDDIR)/go-$(GO_VERSION)/.prepared go.mod force-build
	GOOS=$(word 1,$(subst -, ,$(GO_PLATFORM))) GOARCH=$(word 2,$(subst -, ,$(GO_PLATFORM))) CGO_ENABLED=0 go run ./cmd/wireguard-overlay "$(BUILDDIR)/wireguard-overlay/$(ANDROID_ARCH_NAME)"
	go build -modfile="$(BUILDDIR)/wireguard-overlay/$(ANDROID_ARCH_NAME)/go.mod" -overlay="$(BUILDDIR)/wireguard-overlay/$(ANDROID_ARCH_NAME)/overlay.json" -tags linux -ldflags="-X golang.zx2c4.com/wireguard/android/uapiserver.socketDirectory=/data/data/$(ANDROID_PACKAGE_NAME)/cache/wireguard -buildid=" -v -trimpath -buildvcs=false -o "$@" -buildmode c-shared

.DELETE_ON_ERROR:
