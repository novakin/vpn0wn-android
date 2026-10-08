/* SPDX-License-Identifier: Apache-2.0 */

package androidbind

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

func quietDevice(t *testing.T, bind conn.Bind) (*device.Device, *tuntest.ChannelTUN) {
	t.Helper()
	tunnel := tuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	d := device.NewDevice(tunnel.TUN(), bind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() {
		if b, ok := bind.(*Bind); ok {
			b.Shutdown()
		}
		d.Close()
	})
	return d, tunnel
}

func configPort(t *testing.T, d *device.Device) uint16 {
	t.Helper()
	config, err := d.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(config, "\n") {
		if value, ok := strings.CutPrefix(line, "listen_port="); ok {
			port, err := strconv.ParseUint(value, 10, 16)
			if err != nil {
				t.Fatal(err)
			}
			return uint16(port)
		}
	}
	return 0
}

func TestFailedProtectionRetainsConfiguredPort(t *testing.T) {
	var calls int
	var generations []*fakeBind
	b := New(func(base conn.Bind, _ int) error {
		generations = append(generations, base.(*fakeBind))
		calls++
		if calls == 1 {
			return errors.New("protection denied")
		}
		return nil
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _ := quietDevice(t, b)
	if err := d.IpcSet("listen_port=51820\n"); err != nil {
		t.Fatal(err)
	}
	if err := d.Up(); err == nil {
		t.Fatal("failed protection accepted")
	}
	if port := configPort(t, d); port != 51820 {
		t.Fatalf("lost configured port: %d", port)
	}
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	if len(generations) < 2 || generations[1].openPort != 51820 || !b.warmup.Load() {
		t.Fatal("retry lost explicit port or warm-up intent")
	}
	if err := d.IpcSet("listen_port=0\n"); err != nil {
		t.Fatal(err)
	}
	if b.warmup.Load() || configPort(t, d) == 0 {
		t.Fatal("explicit zero did not select an automatic port without warm-up")
	}
	if err := d.BindUpdate(); err != nil {
		t.Fatal(err)
	}
	if b.warmup.Load() {
		t.Fatal("automatic rebind enabled warm-up")
	}
}

func TestMarkFailureClosesBindAndKeepsRequestedPort(t *testing.T) {
	var failMark atomic.Bool
	var current *fakeBind
	b := New(func(base conn.Bind, _ int) error { current = base.(*fakeBind); return nil }, func() bool { return true })
	b.newBind = func() conn.Bind {
		base := newFakeBind()
		if failMark.Load() {
			base.markError = syscall.EPERM
		}
		return base
	}
	d, _ := quietDevice(t, b)
	if err := d.IpcSet("listen_port=51820\nfwmark=1\n"); err != nil {
		t.Fatal(err)
	}
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	failMark.Store(true)
	if err := d.BindUpdate(); !errors.Is(err, syscall.EPERM) {
		t.Fatalf("mark failure: %v", err)
	}
	select {
	case <-current.closed:
	default:
		t.Fatal("mark failure retained sockets")
	}
	if port := configPort(t, d); port != 51820 {
		t.Fatalf("mark failure changed port: %d", port)
	}
}

type observedBind struct {
	conn.Bind
	warmups     atomic.Int32
	initiations atomic.Int32
}

func (b *observedBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	for _, packet := range packets {
		if bytes.Equal(packet, []byte{0}) {
			b.warmups.Add(1)
		}
		if isInitiation(packet) {
			b.initiations.Add(1)
		}
	}
	return b.Bind.Send(packets, endpoint)
}

func TestStandardPeerAuthenticatedLoopbackTraffic(t *testing.T) {
	reservation, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	port := reservation.LocalAddr().(*net.UDPAddr).Port
	reservation.Close()
	observed := &observedBind{Bind: conn.NewStdNetBind()}
	var protection atomic.Int32
	bind := New(func(conn.Bind, int) error { protection.Add(1); return nil }, func() bool { return true })
	bind.newBind = func() conn.Bind { return observed }
	client, clientTun := quietDevice(t, bind)
	server, serverTun := quietDevice(t, conn.NewStdNetBind())
	var private [2][32]byte
	var public [2][]byte
	for i := range private {
		if _, err := rand.Read(private[i][:]); err != nil {
			t.Fatal(err)
		}
		public[i], err = curve25519.X25519(private[i][:], curve25519.Basepoint)
		if err != nil {
			t.Fatal(err)
		}
	}
	for i, d := range []*device.Device{client, server} {
		listenPort := 0
		if i == 0 {
			listenPort = port
		}
		config := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.0.0.%d/32\n", hex.EncodeToString(private[i][:]), listenPort, hex.EncodeToString(public[i^1]), (i^1)+1)
		if err := d.IpcSet(config); err != nil {
			t.Fatal("synthetic peer configuration rejected")
		}
		if err := d.Up(); err != nil {
			t.Fatal(err)
		}
	}
	if configPort(t, client) != uint16(port) {
		t.Fatal("native explicit port changed")
	}
	endpoint := fmt.Sprintf("public_key=%s\nendpoint=127.0.0.1:%d\n", hex.EncodeToString(public[1]), configPort(t, server))
	if err := client.IpcSet(endpoint); err != nil {
		t.Fatal("synthetic endpoint rejected")
	}
	for i, pair := range [][2]*tuntest.ChannelTUN{{clientTun, serverTun}, {serverTun, clientTun}} {
		message := tuntest.Ping(netip.MustParseAddr(fmt.Sprintf("10.0.0.%d", (i^1)+1)), netip.MustParseAddr(fmt.Sprintf("10.0.0.%d", i+1)))
		select {
		case pair[0].Outbound <- message:
		case <-time.After(5 * time.Second):
			t.Fatal("TUN send stalled")
		}
		select {
		case received := <-pair[1].Inbound:
			if !bytes.Equal(message, received) {
				t.Fatal("authenticated packet changed")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("authenticated traffic did not reach standard peer")
		}
	}
	if protection.Load() != 1 || observed.initiations.Load() == 0 || observed.warmups.Load() != observed.initiations.Load() {
		t.Fatal("loopback did not exercise protected per-initiation warm-up")
	}
}

func TestConcurrentCloseAndSendsSettle(t *testing.T) {
	for i := 0; i < 30; i++ {
		b := openFake(t, newFakeBind(), func() bool { return true })
		b.ListenPortChanged(51820)
		var workers sync.WaitGroup
		for j := 0; j < 4; j++ {
			workers.Add(1)
			go func() { defer workers.Done(); _ = b.Send([][]byte{initiation()}, nil) }()
		}
		b.Shutdown()
		workers.Wait()
	}
}
