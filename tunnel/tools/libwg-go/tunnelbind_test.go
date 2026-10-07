/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"errors"
	"net"
	"sync"
	"testing"

	"golang.zx2c4.com/wireguard/conn"
)

func TestTunnelBindAllowsRebindBeforeTerminalShutdown(t *testing.T) {
	b := &tunnelBind{base: conn.NewStdNetBind()}
	t.Cleanup(func() { b.Shutdown() })
	if _, port, err := b.Open(0); err != nil || port == 0 {
		t.Fatalf("initial open: port=%d error=%v", port, err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	if _, port, err := b.Open(0); err != nil || port == 0 {
		t.Fatalf("ordinary rebind: port=%d error=%v", port, err)
	}
	fd4, err4 := b.PeekLookAtSocketFd4()
	fd6, err6 := b.PeekLookAtSocketFd6()
	if (err4 != nil || fd4 < 0) && (err6 != nil || fd6 < 0) {
		t.Fatalf("real Android descriptors: IPv4=%d/%v IPv6=%d/%v", fd4, err4, fd6, err6)
	}
	b.Shutdown()
	if _, _, err := b.Open(0); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("terminal reopen accepted: %v", err)
	}
	if err := b.SetMark(0); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("terminal mark accepted: %v", err)
	}
	if _, err := b.PeekLookAtSocketFd4(); !errors.Is(err, net.ErrClosed) {
		t.Fatal("terminal IPv4 descriptor returned")
	}
	if _, err := b.PeekLookAtSocketFd6(); !errors.Is(err, net.ErrClosed) {
		t.Fatal("terminal IPv6 descriptor returned")
	}
}

type blockedSendBind struct {
	conn.Bind
	entered chan struct{}
	closed  chan struct{}
	once    sync.Once
}

func (b *blockedSendBind) Send([][]byte, conn.Endpoint) error {
	close(b.entered)
	<-b.closed
	return net.ErrClosed
}

func (b *blockedSendBind) Close() error {
	b.once.Do(func() { close(b.closed) })
	return b.Bind.Close()
}

func TestTunnelBindShutdownCancelsSendWithoutWaitingForItsLock(t *testing.T) {
	base := &blockedSendBind{Bind: conn.NewStdNetBind(), entered: make(chan struct{}), closed: make(chan struct{})}
	b := &tunnelBind{base: base}
	t.Cleanup(func() { base.Close() })
	sent := make(chan struct{})
	go func() { b.Send([][]byte{{1}}, nil); close(sent) }()
	awaitHandle(t, base.entered)
	closed := make(chan struct{})
	go func() { b.Shutdown(); close(closed) }()
	awaitHandle(t, closed)
	awaitHandle(t, sent)
}

func TestTunnelBindConcurrentRealDescriptorMarkRebindAndShutdown(t *testing.T) {
	for i := 0; i < 30; i++ {
		b := &tunnelBind{base: conn.NewStdNetBind()}
		if _, _, err := b.Open(0); err != nil {
			t.Fatal(err)
		}
		var workers sync.WaitGroup
		start := make(chan struct{})
		for j := 0; j < 4; j++ {
			workers.Add(1)
			go func(kind int) {
				defer workers.Done()
				<-start
				for attempt := 0; attempt < 20; attempt++ {
					switch kind {
					case 0:
						b.Close()
						b.Open(0)
					case 1:
						b.PeekLookAtSocketFd4()
						b.PeekLookAtSocketFd6()
					case 2:
						b.SetMark(0)
					case 3:
						b.Shutdown()
					}
				}
			}(j)
		}
		close(start)
		done := make(chan struct{})
		go func() { workers.Wait(); close(done) }()
		awaitHandle(t, done)
		if _, _, err := b.Open(0); !errors.Is(err, net.ErrClosed) {
			t.Fatal("racing reopen escaped terminal shutdown")
		}
	}
}

func TestNativeLookupKindsRejectClosedAndReplacedHandles(t *testing.T) {
	table := handleTable{items: make(map[int32]*TunnelHandle)}
	for i := 0; i < 30; i++ {
		h := testHandle(t)
		if err := h.device.Up(); err != nil {
			t.Fatal(err)
		}
		id := table.add(h)
		if _, ok := table.config(id); !ok {
			t.Fatal("active configuration query failed")
		}
		if table.socketV4(id) < 0 && table.socketV6(id) < 0 {
			t.Fatal("active descriptor queries lost both families")
		}
		var workers sync.WaitGroup
		start := make(chan struct{})
		for j := 0; j < 9; j++ {
			workers.Add(1)
			go func(kind int) {
				defer workers.Done()
				<-start
				switch kind % 3 {
				case 0:
					table.config(id)
				case 1:
					table.socketV4(id)
				case 2:
					table.socketV6(id)
				}
			}(j)
		}
		close(start)
		table.remove(id).closeNative()
		workers.Wait()
		replacement := testHandle(t)
		replacementID := table.add(replacement)
		if _, ok := table.config(id); ok || table.socketV4(id) != -1 || table.socketV6(id) != -1 {
			t.Fatal("late lookup acquired replacement handle")
		}
		table.remove(replacementID).closeNative()
	}
}
