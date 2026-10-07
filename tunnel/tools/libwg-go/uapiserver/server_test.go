/* SPDX-License-Identifier: Apache-2.0 */

package uapiserver

import (
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

const timeout = 5 * time.Second

func listen(t *testing.T) net.Listener {
	t.Helper()
	l, err := listenSocket(filepath.Join(t.TempDir(), "fixture.sock"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	return l
}

func connect(t *testing.T, l net.Listener) net.Conn {
	t.Helper()
	c, err := net.DialTimeout(l.Addr().Network(), l.Addr().String(), timeout)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func await(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(timeout):
		t.Fatal("native UAPI operation did not settle")
	}
}

func TestCloseUnblocksIdleClients(t *testing.T) {
	l := listen(t)
	entered := make(chan struct{})
	finished := make(chan struct{})
	s := New(l, func(c net.Conn) {
		close(entered)
		io.Copy(io.Discard, c)
		close(finished)
	})
	t.Cleanup(func() { s.Close() })
	c := connect(t, l)
	await(t, entered)
	closed := make(chan struct{})
	go func() { s.Close(); close(closed) }()
	await(t, closed)
	await(t, finished)
	if len(s.clients) != 0 {
		t.Fatal("UAPI client retained after close")
	}
	c.SetReadDeadline(time.Now().Add(timeout))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("accepted client remained open")
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestCloseDrainsHandlers(t *testing.T) {
	l := listen(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	s := New(l, func(c net.Conn) {
		close(entered)
		<-release
	})
	c := connect(t, l)
	await(t, entered)
	closed := make(chan struct{})
	go func() { s.Close(); close(closed) }()
	c.SetReadDeadline(time.Now().Add(timeout))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("close did not cancel client I/O")
	}
	select {
	case <-closed:
		t.Fatal("close returned while handler still owned resources")
	default:
	}
	close(release)
	await(t, closed)
}

func TestCloseSettlesPartialWireGuardRequest(t *testing.T) {
	l := listen(t)
	tun := tuntest.NewChannelTUN()
	wg := device.NewDevice(tun.TUN(), conn.NewStdNetBind(), device.NewLogger(device.LogLevelSilent, ""))
	blocked := make(chan struct{})
	s := New(l, func(c net.Conn) {
		wg.IpcHandle(&observedConn{Conn: c, blocked: blocked})
	})
	t.Cleanup(func() { s.Close(); wg.Close() })
	c := connect(t, l)
	if _, err := fmt.Fprint(c, "set=1\nlisten_port=12345\n"); err != nil {
		t.Fatal(err)
	}
	await(t, blocked)
	queryDone := make(chan struct{})
	go func() { wg.IpcGet(); close(queryDone) }()
	closed := make(chan struct{})
	go func() { s.Close(); wg.Close(); close(closed) }()
	await(t, closed)
	await(t, queryDone)
}

type observedConn struct {
	net.Conn
	blocked chan struct{}
	reads   int
}

func (c *observedConn) Read(buffer []byte) (int, error) {
	c.reads++
	if c.reads == 2 {
		close(c.blocked)
	}
	return c.Conn.Read(buffer)
}

func TestConcurrentAdmissionAndClose(t *testing.T) {
	for i := 0; i < 30; i++ {
		l := listen(t)
		s := New(l, func(c net.Conn) { io.Copy(io.Discard, c) })
		var operations sync.WaitGroup
		start := make(chan struct{})
		for j := 0; j < 6; j++ {
			operations.Add(1)
			go func() {
				defer operations.Done()
				<-start
				c, err := net.DialTimeout(l.Addr().Network(), l.Addr().String(), timeout)
				if err == nil {
					defer c.Close()
					io.Copy(io.Discard, c)
				}
			}()
		}
		for j := 0; j < 3; j++ {
			operations.Add(1)
			go func() { defer operations.Done(); <-start; s.Close() }()
		}
		close(start)
		done := make(chan struct{})
		go func() { operations.Wait(); close(done) }()
		await(t, done)
		if len(s.clients) != 0 {
			t.Fatal("concurrent close retained clients")
		}
	}
}

func TestUnixListenerRefusesActiveSocket(t *testing.T) {
	l := listen(t)
	if replacement, err := listenSocket(l.Addr().String()); err == nil {
		replacement.Close()
		t.Fatal("active UAPI socket was replaced")
	}
	if _, err := os.Stat(l.Addr().String()); err != nil {
		t.Fatal("active socket path was removed", err)
	}
	connect(t, l)
}

func TestUnixListenerReusesStaleSocketAndUnlinks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fixture.sock")
	stale, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	stale.SetUnlinkOnClose(false)
	stale.Close()
	l, err := listenSocket(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("UAPI permissions %o; want 600", info.Mode().Perm())
	}
	l.Close()
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("closed UAPI socket path retained", err)
	}
}

func TestUnixListenerPreservesNonSocketPath(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fixture.sock")
	if err := os.WriteFile(path, []byte("preserve"), 0600); err != nil {
		t.Fatal(err)
	}
	if l, err := listenSocket(path); err == nil {
		l.Close()
		t.Fatal("regular file accepted as UAPI socket")
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "preserve" {
		t.Fatal("regular file changed", err)
	}
}
