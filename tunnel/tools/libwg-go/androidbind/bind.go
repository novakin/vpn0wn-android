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
	lifecycle    sync.Mutex
	mu           sync.Mutex
	ready        conn.Bind
	stopped      bool
	warmup       atomic.Bool
	protect      func(conn.Bind, int) error
	active       func() bool
	newBind      func() conn.Bind
	observe      func([32]byte, int, bool, string, uint64)
	capture      *Capture
	readyCapture *Capture
	generation   uint64
}

// Capture belongs to one serialized transport operation and every family opened by it.
type Capture struct {
	Prepare   func(conn.Bind, int) error
	Publish   func(uint64) error
	Confirm   func(uint64) error
	Empty     func(uint64) error
	Release   func()
	Available bool
}

var ErrObsolete = errors.New("obsolete transport generation")

func (b *Bind) Generation() uint64 {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.generation
}

func (b *Bind) Snapshot() (uint64, bool) {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.generation, !b.stopped && b.ready != nil
}

// ReplaceCapture runs inside the device IPC owner, before old captured I/O closes.
func (b *Bind) ReplaceCapture(expected uint64, capture *Capture) (bool, error) {
	b.lifecycle.Lock()
	defer b.lifecycle.Unlock()
	b.mu.Lock()
	if b.stopped || b.generation != expected {
		b.mu.Unlock()
		return false, ErrObsolete
	}
	previous := b.capture
	b.generation++
	b.capture = capture
	b.mu.Unlock()
	if err := b.closeReady(); err != nil {
		if previous != nil && previous.Release != nil {
			previous.Release()
		}
		return true, err
	}
	if previous != nil && previous.Release != nil {
		previous.Release()
	}
	return true, nil
}

func (b *Bind) SetInitialCapture(capture *Capture) {
	b.mu.Lock()
	b.capture = capture
	b.mu.Unlock()
}

const (
	HandshakeAttempt = iota + 1
	HandshakeSent
	HandshakeSendFailed
	HandshakeAuthenticated
	HandshakeExhausted
)

func (b *Bind) SetHandshakeObserver(observe func([32]byte, int, bool, string, uint64)) {
	b.mu.Lock()
	b.observe = observe
	b.mu.Unlock()
}

func (b *Bind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string, episode uint64) {
	b.mu.Lock()
	observe, stopped := b.observe, b.stopped
	b.mu.Unlock()
	if observe != nil && !stopped {
		observe(peer, event, retry, endpoint, episode)
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
	stopped, opened, capture, generation := b.stopped, b.ready != nil, b.capture, b.generation
	b.mu.Unlock()
	if stopped {
		return nil, 0, net.ErrClosed
	}
	if opened {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	b.mu.Lock()
	b.generation++
	generation = b.generation
	b.mu.Unlock()
	if capture != nil && !capture.Available {
		if capture.Empty != nil {
			capture.Empty(generation)
		}
		return nil, 0, net.ErrClosed
	}
	base := b.newBind()
	fns, actual, err := base.Open(port)
	if err == nil {
		if capture != nil {
			err = capture.Prepare(base, len(fns))
		} else {
			err = b.protect(base, len(fns))
		}
	}
	if err != nil {
		base.Close()
		if capture != nil && capture.Empty != nil {
			capture.Empty(generation)
		}
		return nil, 0, err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.stopped || b.generation != generation || b.capture != capture || !b.active() {
		base.Close()
		return nil, 0, net.ErrClosed
	}
	if capture != nil {
		if err := capture.Publish(generation); err != nil {
			base.Close()
			if capture.Empty != nil {
				capture.Empty(generation)
			}
			return nil, 0, err
		}
	}
	b.ready = base
	b.readyCapture = capture
	if capture != nil && capture.Confirm != nil {
		if err := capture.Confirm(generation); err != nil {
			b.ready = nil
			b.readyCapture = nil
			base.Close()
			if capture.Empty != nil {
				capture.Empty(generation)
			}
			return nil, 0, err
		}
	}
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
	capture, generation := b.readyCapture, b.generation
	b.ready = nil
	b.readyCapture = nil
	b.mu.Unlock()
	if base != nil {
		err := base.Close()
		if capture != nil && capture.Empty != nil {
			if reportErr := capture.Empty(generation); err == nil {
				err = reportErr
			}
		}
		return err
	}
	return nil
}

// Shutdown precedes Device.Close: a blocked send may hold the device's network read lock.
func (b *Bind) Shutdown() error {
	b.mu.Lock()
	b.stopped = true
	b.mu.Unlock()
	err := b.Close()
	b.lifecycle.Lock()
	b.mu.Lock()
	capture := b.capture
	b.capture = nil
	b.mu.Unlock()
	b.lifecycle.Unlock()
	if capture != nil && capture.Release != nil {
		capture.Release()
	}
	return err
}

func (b *Bind) IsStopped() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.stopped
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
