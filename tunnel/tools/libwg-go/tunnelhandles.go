/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * Modified for vpn0wn.
 */

package main

// struct wg_context;
import "C"

import (
	"math"
	"net"
	"sync"

	"golang.zx2c4.com/wireguard/android/androidbind"
	"golang.zx2c4.com/wireguard/android/uapiserver"
)

type tunnelDevice interface {
	IpcSet(string) error
	IpcGet() (string, error)
	IpcHandle(net.Conn)
	Up() error
	Close()
	DisableSomeRoamingForBrokenMobileSemantics()
	Rebind() error
	RebindTransport(func() error) error
	Recover([32]byte, uint64, string, func() bool) int
}

type TunnelHandle struct {
	device  tunnelDevice
	bind    *androidbind.Bind
	uapi    *uapiserver.Server
	context *C.struct_wg_context
	queries sync.WaitGroup
}

func (h *TunnelHandle) closeNative() {
	h.bind.Shutdown()
	if h.uapi != nil {
		h.uapi.Close()
	}
	h.queries.Wait()
	h.device.Close()
}

type handleTable struct {
	mu     sync.Mutex
	items  map[int32]*TunnelHandle
	nextID int64
}

var tunnelHandles = handleTable{items: make(map[int32]*TunnelHandle)}

func (t *handleTable) add(h *TunnelHandle) int32 {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.nextID > math.MaxInt32 {
		return -1
	}
	// Never reuse an ID: a delayed JNI lookup must not acquire a replacement context.
	id := int32(t.nextID)
	t.nextID++
	t.items[id] = h
	return id
}

func (t *handleTable) acquire(id int32) *TunnelHandle {
	t.mu.Lock()
	defer t.mu.Unlock()
	h := t.items[id]
	if h != nil {
		h.queries.Add(1)
	}
	return h
}

func (t *handleTable) remove(id int32) *TunnelHandle {
	t.mu.Lock()
	defer t.mu.Unlock()
	h := t.items[id]
	delete(t.items, id)
	return h
}
