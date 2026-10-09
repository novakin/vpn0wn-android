/* SPDX-License-Identifier: Apache-2.0 */

package androidbind

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"syscall"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
)

type Bind struct {
	lifecycle sync.Mutex
	mu        sync.Mutex
	ready     conn.Bind
	stopped   bool
	warmup    atomic.Bool
	protect   func(conn.Bind, int) error
	active    func() bool
	newBind   func() conn.Bind
	observe   func([32]byte, int, bool, string)
}

const (
	HandshakeAttempt = iota + 1
	HandshakeSent
	HandshakeSendFailed
	HandshakeAuthenticated
	HandshakeExhausted
)

func (b *Bind) SetHandshakeObserver(observe func([32]byte, int, bool, string)) {
	b.mu.Lock()
	b.observe = observe
	b.mu.Unlock()
}

func (b *Bind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string) {
	b.mu.Lock()
	observe, stopped := b.observe, b.stopped
	b.mu.Unlock()
	if observe != nil && !stopped {
		observe(peer, event, retry, endpoint)
	}
}

func New(protect func(conn.Bind, int) error, active func() bool) *Bind {
	return &Bind{protect: protect, active: active, newBind: conn.NewStdNetBind}
}

// ListenPortChanged tracks explicit UAPI intent, not an automatically selected port on reopen.
func (b *Bind) ListenPortChanged(port uint16) {
	b.warmup.Store(port != 0)
}

func (b *Bind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	stopped, opened := b.stopped, b.ready != nil
	b.mu.Unlock()
	if stopped {
		return nil, 0, net.ErrClosed
	}
	if opened {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	base := b.newBind()
	fns, actual, err := base.Open(port)
	if err == nil {
		err = b.protect(base, len(fns))
	}
	if err != nil {
		base.Close()
		return nil, 0, err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped || !b.active() {
		base.Close()
		return nil, 0, net.ErrClosed
	}
	b.ready = base
	return fns, actual, nil
}

func (b *Bind) Close() error {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	return b.closeReady()
}

func (b *Bind) closeReady() error {
	b.mu.Lock()
	base := b.ready
	b.ready = nil
	b.mu.Unlock()
	if base != nil {
		return base.Close()
	}
	return nil
}

// Shutdown precedes Device.Close: a blocked send may hold the device's network read lock.
func (b *Bind) Shutdown() error {
	b.mu.Lock()
	b.stopped = true
	b.mu.Unlock()
	return b.Close()
}

func (b *Bind) SetMark(mark uint32) error {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	base := b.ready
	b.mu.Unlock()
	if base == nil {
		return net.ErrClosed
	}
	return base.SetMark(mark)
}

func (b *Bind) Send(bufs [][]byte, endpoint conn.Endpoint) error {
	b.mu.Lock()
	base := b.ready
	b.mu.Unlock()
	if base == nil {
		return net.ErrClosed
	}
	if !b.warmup.Load() {
		return base.Send(bufs, endpoint)
	}
	start := 0
	for i, packet := range bufs {
		if isInitiation(packet) {
			if i > start {
				if err := base.Send(bufs[start:i], endpoint); err != nil {
					return err
				}
			}
			if !b.active() || !b.isCurrent(base) {
				return net.ErrClosed
			}
			err := base.Send([][]byte{{0}}, endpoint)
			if err != nil && !warmupIOFailure(err) {
				return err
			}
			if !b.active() || !b.isCurrent(base) {
				return net.ErrClosed
			}
			if err := base.Send(bufs[i:i+1], endpoint); err != nil {
				return err
			}
			start = i + 1
		}
	}
	if start < len(bufs) {
		return base.Send(bufs[start:], endpoint)
	}
	return nil
}

func ProtectSockets(base conn.Bind, socketCount int, protect func(int) bool) error {
	sockets, ok := base.(conn.PeekLookAtSocketFd)
	if !ok {
		return errors.New("bind has no Android socket descriptors")
	}
	protected := 0
	for _, peek := range []func() (int, error){sockets.PeekLookAtSocketFd4, sockets.PeekLookAtSocketFd6} {
		fd, err := peek()
		// The pinned Android methods are overlaid to return EINVAL for an unavailable family.
		if errors.Is(err, syscall.EINVAL) {
			continue
		}
		if err != nil {
			return err
		}
		if fd < 0 || !protect(fd) {
			return errors.New("native UDP socket protection failed")
		}
		protected++
	}
	if protected != socketCount || protected == 0 {
		return fmt.Errorf("protected %d sockets for %d receive functions", protected, socketCount)
	}
	return nil
}

func (b *Bind) isCurrent(base conn.Bind) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return !b.stopped && b.ready == base
}

func isInitiation(packet []byte) bool {
	return len(packet) == device.MessageInitiationSize && binary.LittleEndian.Uint32(packet[:4]) == device.MessageInitiationType
}

func warmupIOFailure(err error) bool {
	var offloadError conn.ErrUDPGSODisabled
	if errors.As(err, &offloadError) {
		if offloadError.RetryErr == nil {
			return true
		}
		err = offloadError.RetryErr
	}
	if errors.Is(err, net.ErrClosed) || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, syscall.EBADF) {
		return false
	}
	var networkError *net.OpError
	var socketError syscall.Errno
	return errors.As(err, &networkError) || errors.As(err, &socketError)
}

func (b *Bind) ParseEndpoint(endpoint string) (conn.Endpoint, error) {
	return b.newBind().ParseEndpoint(endpoint)
}

func (b *Bind) BatchSize() int {
	return b.newBind().BatchSize()
}
