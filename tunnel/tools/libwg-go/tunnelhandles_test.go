/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"fmt"
	"math"
	"net"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/android/androidbind"
	"golang.zx2c4.com/wireguard/android/uapiserver"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

func testHandle(t *testing.T) *TunnelHandle {
	t.Helper()
	tunnel := tuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	bind := androidbind.New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	wg := device.NewDevice(tunnel.TUN(), bind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() { bind.Shutdown(); wg.Close() })
	return &TunnelHandle{device: wg, bind: bind}
}

func awaitHandle(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("native handle operation did not settle")
	}
}

type partialRequestConn struct {
	net.Conn
	blocked chan struct{}
	reads   int
}

func (c *partialRequestConn) Read(buffer []byte) (int, error) {
	c.reads++
	if c.reads == 2 {
		close(c.blocked)
	}
	return c.Conn.Read(buffer)
}

func TestHandleTeardownCancelsPartialRequestBeforeQueryDrain(t *testing.T) {
	h := testHandle(t)
	table := handleTable{items: make(map[int32]*TunnelHandle)}
	id := table.add(h)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: filepath.Join(t.TempDir(), "uapi.sock"), Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	blocked := make(chan struct{})
	h.uapi = uapiserver.New(listener, func(c net.Conn) {
		h.device.IpcHandle(&partialRequestConn{Conn: c, blocked: blocked})
	})
	t.Cleanup(func() { h.uapi.Close() })
	client, err := net.Dial("unix", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	if _, err := fmt.Fprint(client, "set=1\nlisten_port=12345\n"); err != nil {
		t.Fatal(err)
	}
	awaitHandle(t, blocked)
	query := table.acquire(id)
	queryDone := make(chan struct{})
	go func() {
		defer close(queryDone)
		defer query.queries.Done()
		query.device.IpcGet()
	}()
	select {
	case <-queryDone:
		t.Fatal("partial request did not block native query")
	case <-time.After(50 * time.Millisecond):
	}
	closed := make(chan struct{})
	go func() { table.remove(id).closeNative(); close(closed) }()
	awaitHandle(t, closed)
	awaitHandle(t, queryDone)
	if table.acquire(id) != nil {
		t.Fatal("closed handle admitted a query")
	}
}

func TestHandleCloseDrainsAdmittedQueryBeforeDeviceRelease(t *testing.T) {
	h := testHandle(t)
	table := handleTable{items: make(map[int32]*TunnelHandle)}
	id := table.add(h)
	query := table.acquire(id)
	closing := table.remove(id)
	closed := make(chan struct{})
	go func() { closing.closeNative(); close(closed) }()
	select {
	case <-closed:
		t.Fatal("close released device while a query remained admitted")
	case <-time.After(50 * time.Millisecond):
	}
	query.queries.Done()
	awaitHandle(t, closed)
}

func TestHandleConcurrentQueriesCloseAndReplacementIsolation(t *testing.T) {
	table := handleTable{items: make(map[int32]*TunnelHandle)}
	for i := 0; i < 30; i++ {
		h := testHandle(t)
		id := table.add(h)
		var workers sync.WaitGroup
		var releases atomic.Int32
		start := make(chan struct{})
		for j := 0; j < 8; j++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				<-start
				if query := table.acquire(id); query != nil {
					defer query.queries.Done()
					query.device.IpcGet()
				}
			}()
		}
		for j := 0; j < 3; j++ {
			workers.Add(1)
			go func() {
				defer workers.Done()
				<-start
				if closing := table.remove(id); closing != nil {
					closing.closeNative()
					releases.Add(1)
				}
			}()
		}
		close(start)
		done := make(chan struct{})
		go func() { workers.Wait(); close(done) }()
		awaitHandle(t, done)
		if releases.Load() != 1 || table.acquire(id) != nil {
			t.Fatal("duplicate release or late query admission")
		}
		replacement := testHandle(t)
		replacementID := table.add(replacement)
		if replacementID <= id || table.acquire(id) != nil || table.remove(id) != nil {
			t.Fatal("stale ID acquired or removed replacement")
		}
		query := table.acquire(replacementID)
		if query != replacement {
			t.Fatal("replacement lookup changed identity")
		}
		query.queries.Done()
		table.remove(replacementID).closeNative()
	}
}

func TestHandleIDExhaustionNeverReusesClosedID(t *testing.T) {
	table := handleTable{items: make(map[int32]*TunnelHandle), nextID: math.MaxInt32}
	h := &TunnelHandle{}
	if id := table.add(h); id != math.MaxInt32 {
		t.Fatal("last valid handle rejected")
	}
	table.remove(math.MaxInt32)
	if table.add(h) != -1 {
		t.Fatal("exhaustion reused a stale handle ID")
	}
}
