/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"errors"
	"fmt"
	"math"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	awgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgtuntest "github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
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

func testAmneziaHandle(t *testing.T) *TunnelHandle {
	t.Helper()
	tunnel := awgtuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	bind := androidbind.New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	awg := awgdevice.NewDevice(tunnel.TUN(), androidbind.Amnezia{Bind: bind}, awgdevice.NewLogger(awgdevice.LogLevelSilent, ""))
	t.Cleanup(func() { bind.Shutdown(); awg.Close() })
	return &TunnelHandle{device: awg, bind: bind}
}

func TestHandleHostnameRecoveryAcrossEngines(t *testing.T) {
	for _, engine := range []struct {
		name   string
		create func(*testing.T) *TunnelHandle
	}{{"WireGuard", testHandle}, {"AmneziaWG", testAmneziaHandle}} {
		t.Run(engine.name, func(t *testing.T) {
			for _, test := range []struct {
				name, replacement string
				current           bool
			}{{"valid", "127.0.0.2:51820", true}, {"generation", "127.0.0.2:51820", false},
				{"hostname", "vpn.example.invalid:51820", true}, {"port", "127.0.0.2:51821", true}} {
				t.Run(test.name, func(t *testing.T) {
					h := engine.create(t)
					key, other, private := [32]byte{9}, [32]byte{10}, [32]byte{1}
					if err := h.device.IpcSet(fmt.Sprintf("private_key=%x\npublic_key=%x\nendpoint=127.0.0.1:51820\npublic_key=%x\nendpoint=127.0.0.3:51821\n", private, key, other)); err != nil {
						t.Fatal(err)
					}
					if err := h.device.Up(); err != nil {
						t.Fatal(err)
					}
					fixture := h.device.(interface {
						ExhaustPeerForTest([32]byte) (uint64, error)
					})
					episode, err := fixture.ExhaustPeerForTest(key)
					if err != nil || episode == 0 {
						t.Fatalf("fixture exhaustion: token=%d error=%v", episode, err)
					}
					before, err := h.device.IpcGet()
					if err != nil {
						t.Fatal(err)
					}
					generation := h.bind.Generation()
					result := h.device.Recover(key, episode, "127.0.0.1:51820", test.replacement,
						func() bool { return test.current && h.bind.Generation() == generation }, func() bool { return false })
					if test.name != "valid" {
						after, err := h.device.IpcGet()
						if result != device.RecoveryObsolete || err != nil || h.bind.Generation() != generation ||
							!strings.Contains(after, "endpoint=127.0.0.1:51820\n") || !strings.Contains(after, "endpoint=127.0.0.3:51821\n") {
							t.Fatal("rejected resolution mutated peer or transport")
						}
						result = h.device.Recover(key, episode, "127.0.0.1:51820", "127.0.0.2:51820",
							func() bool { return h.bind.Generation() == generation }, func() bool { return false })
					}
					after, err := h.device.IpcGet()
					if err != nil || result != device.RecoveryInitiationSkipped || h.bind.Generation() <= generation {
						t.Fatalf("replacement recovery: outcome=%d error=%v", result, err)
					}
					if !strings.Contains(after, "endpoint=127.0.0.2:51820\n") || !strings.Contains(after, "endpoint=127.0.0.3:51821\n") {
						t.Fatal("recovery failed to isolate the claimed peer")
					}
					for _, line := range strings.Split(before, "\n") {
						if strings.HasPrefix(line, "listen_port=") && !strings.Contains(after, line+"\n") {
							t.Fatal("recovery changed the local port")
						}
					}
					if repeated := h.device.Recover(key, episode, "127.0.0.2:51820", "127.0.0.1:51820",
						func() bool { return true }, func() bool { return false }); repeated != device.RecoveryObsolete {
						t.Fatal("recovery reused a claimed episode")
					}
				})
			}
		})
	}
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
	directory, err := os.MkdirTemp("", "wg-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(directory) })
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: filepath.Join(directory, "uapi.sock"), Net: "unix"})
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

func TestHandleTeardownCancelsRebindBeforeBorrowDrain(t *testing.T) {
	testHandleTeardownCancelsTransportBeforeBorrowDrain(t, false)
}

func TestHandleTeardownCancelsRecoveryBeforeBorrowDrain(t *testing.T) {
	testHandleTeardownCancelsTransportBeforeBorrowDrain(t, true)
}

func testHandleTeardownCancelsTransportBeforeBorrowDrain(t *testing.T, recovery bool) {
	entered, releaseProtection := make(chan struct{}), make(chan struct{})
	var protection atomic.Int32
	var released sync.Once
	bind := androidbind.New(func(conn.Bind, int) error {
		if protection.Add(1) == 2 {
			close(entered)
			<-releaseProtection
		}
		return nil
	}, func() bool { return true })
	tunnel := tuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	wg := device.NewDevice(tunnel.TUN(), bind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() { bind.Shutdown(); wg.Close() })
	t.Cleanup(func() { released.Do(func() { close(releaseProtection) }) })
	key := [32]byte{9}
	if recovery {
		privateKey := [32]byte{1}
		if err := wg.IpcSet(fmt.Sprintf("private_key=%x\npublic_key=%x\nendpoint=127.0.0.1:51820\n", privateKey, key)); err != nil {
			t.Fatal(err)
		}
	}
	if err := wg.Up(); err != nil {
		t.Fatal(err)
	}
	var episode uint64
	if recovery {
		var err error
		episode, err = wg.ExhaustPeerForTest(key)
		if err != nil || episode == 0 {
			t.Fatalf("fixture exhaustion: token=%d error=%v", episode, err)
		}
	}
	h := &TunnelHandle{device: wg, bind: bind}
	table := handleTable{items: make(map[int32]*TunnelHandle)}
	id := table.add(h)
	borrowed := table.acquire(id)
	releaseBorrow := make(chan struct{})
	var borrowReleased sync.Once
	t.Cleanup(func() { borrowReleased.Do(func() { close(releaseBorrow) }) })
	rebound := make(chan error, 1)
	go func() {
		defer borrowed.queries.Done()
		if recovery {
			result := borrowed.device.Recover(key, episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false })
			if result != device.RecoveryTransportFailed {
				rebound <- fmt.Errorf("cancelled recovery outcome=%d", result)
			} else {
				rebound <- net.ErrClosed
			}
		} else {
			rebound <- borrowed.device.Rebind()
		}
		<-releaseBorrow
	}()
	awaitHandle(t, entered)
	closing := table.remove(id)
	closed := make(chan struct{})
	go func() { closing.closeNative(); close(closed) }()
	deadline := time.Now().Add(5 * time.Second)
	for !bind.IsStopped() {
		if time.Now().After(deadline) {
			t.Fatal("teardown did not stop rebind admission")
		}
		runtime.Gosched()
	}
	if table.acquire(id) != nil {
		t.Fatal("removed handle admitted another rebind")
	}
	released.Do(func() { close(releaseProtection) })
	select {
	case err := <-rebound:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("cancelled native rebind: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("borrowed rebind did not settle")
	}
	select {
	case <-wg.Wait():
		t.Fatal("teardown released device before rebind borrow")
	default:
	}
	borrowReleased.Do(func() { close(releaseBorrow) })
	awaitHandle(t, closed)
	select {
	case <-wg.Wait():
	default:
		t.Fatal("teardown retained device after borrow drain")
	}
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
