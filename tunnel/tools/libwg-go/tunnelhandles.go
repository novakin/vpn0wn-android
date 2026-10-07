/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"math"
	"sync"

	"golang.zx2c4.com/wireguard/android/uapiserver"
	"golang.zx2c4.com/wireguard/device"
)

type TunnelHandle struct {
	device  *device.Device
	bind    *tunnelBind
	uapi    *uapiserver.Server
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
	// Never reuse an ID: a delayed JNI lookup must not acquire a replacement device.
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

func (t *handleTable) config(id int32) (string, bool) {
	h := t.acquire(id)
	if h == nil {
		return "", false
	}
	defer h.queries.Done()
	settings, err := h.device.IpcGet()
	if err != nil {
		return "", false
	}
	return settings, true
}

func (t *handleTable) socketV4(id int32) int32 {
	h := t.acquire(id)
	if h == nil {
		return -1
	}
	defer h.queries.Done()
	fd, err := h.bind.PeekLookAtSocketFd4()
	if err != nil {
		return -1
	}
	return int32(fd)
}

func (t *handleTable) socketV6(id int32) int32 {
	h := t.acquire(id)
	if h == nil {
		return -1
	}
	defer h.queries.Done()
	fd, err := h.bind.PeekLookAtSocketFd6()
	if err != nil {
		return -1
	}
	return int32(fd)
}
