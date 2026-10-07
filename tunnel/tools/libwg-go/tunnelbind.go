/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"net"
	"sync"
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
)

type tunnelBind struct {
	mu      sync.Mutex
	base    conn.Bind
	stopped bool
}

func (b *tunnelBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped {
		return nil, 0, net.ErrClosed
	}
	return b.base.Open(port)
}

func (b *tunnelBind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.base.Close()
}

func (b *tunnelBind) Shutdown() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.stopped = true
	b.base.Close()
}

func (b *tunnelBind) SetMark(mark uint32) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped {
		return net.ErrClosed
	}
	return b.base.SetMark(mark)
}

func (b *tunnelBind) PeekLookAtSocketFd4() (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped {
		return -1, net.ErrClosed
	}
	peek, ok := b.base.(conn.PeekLookAtSocketFd)
	if !ok {
		return -1, syscall.EINVAL
	}
	return peek.PeekLookAtSocketFd4()
}

func (b *tunnelBind) PeekLookAtSocketFd6() (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped {
		return -1, net.ErrClosed
	}
	peek, ok := b.base.(conn.PeekLookAtSocketFd)
	if !ok {
		return -1, syscall.EINVAL
	}
	return peek.PeekLookAtSocketFd6()
}

func (b *tunnelBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	return b.base.Send(packets, endpoint)
}

func (b *tunnelBind) ParseEndpoint(endpoint string) (conn.Endpoint, error) {
	return b.base.ParseEndpoint(endpoint)
}

func (b *tunnelBind) BatchSize() int {
	return b.base.BatchSize()
}
