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
	"runtime"
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
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

type trackedTUN struct {
	tun.Device
	closeCalls atomic.Int32
}

func (tunnel *trackedTUN) Close() error {
	tunnel.closeCalls.Add(1)
	return tunnel.Device.Close()
}

func quietDevice(t *testing.T, bind conn.Bind) (*device.Device, *tuntest.ChannelTUN, *trackedTUN) {
	t.Helper()
	tunnel := tuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	tracked := &trackedTUN{Device: tunnel.TUN()}
	d := device.NewDevice(tracked, bind, device.NewLogger(device.LogLevelSilent, ""))
	t.Cleanup(func() {
		if b, ok := bind.(*Bind); ok {
			b.Shutdown()
		}
		d.Close()
	})
	return d, tunnel, tracked
}

func TestQuietDeviceTracksTUNClosure(t *testing.T) {
	d, _, tunnel := quietDevice(t, conn.NewStdNetBind())
	if tunnel.closeCalls.Load() != 0 {
		t.Fatal("new TUN already closed")
	}
	d.Close()
	if tunnel.closeCalls.Load() != 1 {
		t.Fatal("device closure did not close tracked TUN")
	}
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
	d, _, _ := quietDevice(t, b)
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
	d, _, _ := quietDevice(t, b)
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

func awaitRebind(t *testing.T, done <-chan error) error {
	t.Helper()
	select {
	case err := <-done:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("retained-TUN rebind did not settle")
		return nil
	}
}

func awaitRebindBarrier(t *testing.T, reached <-chan struct{}) {
	t.Helper()
	select {
	case <-reached:
	case <-time.After(5 * time.Second):
		t.Fatal("rebind control did not reach its barrier")
	}
}

func TestRebindCancelsCapturedPeerSendBeforeNetworkLock(t *testing.T) {
	var old, replacement *fakeBind
	entered := make(chan struct{})
	var protected atomic.Int32
	b := New(func(base conn.Bind, _ int) error {
		if protected.Add(1) == 1 {
			old = base.(*fakeBind)
			old.onSend = func([][]byte) error {
				close(entered)
				<-old.closed
				return nil
			}
		} else {
			replacement = base.(*fakeBind)
		}
		return nil
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, tunnel := quietDevice(t, b)
	publicKey := device.NoisePublicKey{1}
	config := fmt.Sprintf("listen_port=51820\npublic_key=%s\nendpoint=127.0.0.1:51821\n", hex.EncodeToString(publicKey[:]))
	if err := d.IpcSet(config); err != nil {
		t.Fatal(err)
	}
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	peer := d.LookupPeer(publicKey)
	if peer == nil {
		t.Fatal("synthetic peer not created")
	}
	sent := make(chan error, 1)
	go func() { sent <- peer.SendBuffers([][]byte{initiation()}) }()
	awaitRebindBarrier(t, entered)
	rebound := make(chan error, 1)
	go func() { rebound <- d.Rebind() }()
	if err := awaitRebind(t, rebound); err != nil {
		t.Fatal(err)
	}
	if err := awaitRebind(t, sent); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("captured send crossed generations: %v", err)
	}
	if protected.Load() != 2 || replacement.openPort != 51820 || configPort(t, d) != 51820 || !b.warmup.Load() {
		t.Fatal("rebind lost protection, explicit port or warm-up intent")
	}
	if len(old.sent()) != 1 || len(replacement.sent()) != 0 {
		t.Fatal("obsolete initiation reached replacement sockets")
	}
	select {
	case <-d.Wait():
		t.Fatal("rebind closed device")
	default:
	}
	if tunnel.closeCalls.Load() != 0 {
		t.Fatal("rebind closed retained TUN")
	}
	if err := peer.SendBuffers([][]byte{initiation()}); err != nil || len(replacement.sent()) != 2 {
		t.Fatal("replacement did not send warm-up and initiation")
	}
}

type rebindRequestConn struct {
	net.Conn
	blocked chan struct{}
	reads   int
}

func (c *rebindRequestConn) Read(buffer []byte) (int, error) {
	c.reads++
	if c.reads == 2 {
		close(c.blocked)
	}
	return c.Conn.Read(buffer)
}

func TestRebindRejectsPartialUAPIWithoutSocketMutation(t *testing.T) {
	var opened atomic.Int32
	b := New(func(conn.Bind, int) error { opened.Add(1); return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, _ := quietDevice(t, b)
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	client, server := net.Pipe()
	t.Cleanup(func() { client.Close(); server.Close() })
	blocked := make(chan struct{})
	requestDone := make(chan struct{})
	go func() {
		defer close(requestDone)
		d.IpcHandle(&rebindRequestConn{Conn: server, blocked: blocked})
	}()
	if _, err := fmt.Fprint(client, "set=1\nlisten_port=51820\n"); err != nil {
		t.Fatal(err)
	}
	awaitRebindBarrier(t, blocked)
	b.mu.Lock()
	base := b.ready.(*fakeBind)
	b.mu.Unlock()
	count := opened.Load()
	if err := d.Rebind(); !errors.Is(err, syscall.EBUSY) {
		t.Fatalf("partial UAPI rebind: %v", err)
	}
	if opened.Load() != count {
		t.Fatal("busy rebind opened sockets")
	}
	select {
	case <-base.closed:
		t.Fatal("busy rebind closed active sockets")
	default:
	}
	client.Close()
	awaitRebindBarrier(t, requestDone)
	if err := d.Rebind(); err != nil || configPort(t, d) != 51820 {
		t.Fatal("rebind did not recover after partial UAPI settled")
	}
}

func TestRebindRejectsConcurrentRecoveryBeforeMutation(t *testing.T) {
	var protection atomic.Int32
	entered, release := make(chan struct{}), make(chan struct{})
	var released sync.Once
	b := New(func(conn.Bind, int) error {
		if protection.Add(1) == 2 {
			close(entered)
			<-release
		}
		return nil
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, _ := quietDevice(t, b)
	t.Cleanup(func() { released.Do(func() { close(release) }) })
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	rebound := make(chan error, 1)
	go func() { rebound <- d.Rebind() }()
	awaitRebindBarrier(t, entered)
	if err := d.Rebind(); !errors.Is(err, syscall.EBUSY) || protection.Load() != 2 {
		t.Fatalf("concurrent recovery did not reject unchanged: %v", err)
	}
	released.Do(func() { close(release) })
	if err := awaitRebind(t, rebound); err != nil {
		t.Fatal(err)
	}
}

func TestRebindProtectionFailurePreservesPortAndRetry(t *testing.T) {
	for _, port := range []uint16{0, 51820} {
		t.Run(strconv.Itoa(int(port)), func(t *testing.T) {
			var denied atomic.Bool
			var generations []*fakeBind
			b := New(func(base conn.Bind, _ int) error {
				generations = append(generations, base.(*fakeBind))
				if denied.Load() {
					return syscall.EPERM
				}
				return nil
			}, func() bool { return true })
			b.newBind = func() conn.Bind { return newFakeBind() }
			d, _, tunnel := quietDevice(t, b)
			if err := d.IpcSet(fmt.Sprintf("listen_port=%d\n", port)); err != nil {
				t.Fatal(err)
			}
			if err := d.Up(); err != nil {
				t.Fatal(err)
			}
			selectedPort := configPort(t, d)
			denied.Store(true)
			if err := d.Rebind(); !errors.Is(err, syscall.EPERM) {
				t.Fatalf("protection failure: %v", err)
			}
			if configPort(t, d) != selectedPort || b.warmup.Load() != (port != 0) {
				t.Fatal("failed rebind lost port or explicit intent")
			}
			select {
			case <-generations[1].closed:
			default:
				t.Fatal("denied generation retained sockets")
			}
			if err := b.Send([][]byte{initiation()}, nil); !errors.Is(err, net.ErrClosed) {
				t.Fatal("failed protection published a sendable generation")
			}
			denied.Store(false)
			if err := d.Rebind(); err != nil || generations[2].openPort != selectedPort || configPort(t, d) != selectedPort {
				t.Fatal("retained device retry lost selected port")
			}
			if tunnel.closeCalls.Load() != 0 {
				t.Fatal("failed rebind closed TUN")
			}
		})
	}
}

func TestRebindShutdownDuringProtectionCannotPublish(t *testing.T) {
	var protection atomic.Int32
	entered, release := make(chan struct{}), make(chan struct{})
	var released sync.Once
	var replacement *fakeBind
	b := New(func(base conn.Bind, _ int) error {
		if protection.Add(1) == 2 {
			replacement = base.(*fakeBind)
			close(entered)
			<-release
		}
		return nil
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, _ := quietDevice(t, b)
	t.Cleanup(func() { released.Do(func() { close(release) }) })
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	rebound := make(chan error, 1)
	go func() { rebound <- d.Rebind() }()
	awaitRebindBarrier(t, entered)
	stopped := make(chan error, 1)
	go func() { stopped <- b.Shutdown() }()
	deadline := time.Now().Add(5 * time.Second)
	for !b.IsStopped() {
		if time.Now().After(deadline) {
			t.Fatal("shutdown did not stop bind admission")
		}
		runtime.Gosched()
	}
	released.Do(func() { close(release) })
	if err := awaitRebind(t, rebound); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("shutdown rebind completion: %v", err)
	}
	if err := awaitRebind(t, stopped); err != nil {
		t.Fatal(err)
	}
	select {
	case <-replacement.closed:
	default:
		t.Fatal("shutdown retained unpublished sockets")
	}
	if err := b.Send([][]byte{initiation()}, nil); !errors.Is(err, net.ErrClosed) {
		t.Fatal("shutdown published sendable sockets")
	}
	if err := d.Rebind(); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("terminal bind reopened: %v", err)
	}
}

func TestRebindRejectsDownAndClosedDevice(t *testing.T) {
	var opened atomic.Int32
	b := New(func(conn.Bind, int) error { opened.Add(1); return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, _ := quietDevice(t, b)
	if err := d.Rebind(); !errors.Is(err, net.ErrClosed) || opened.Load() != 0 {
		t.Fatalf("DOWN device rebind: %v", err)
	}
	b.Shutdown()
	d.Close()
	if err := d.Rebind(); !errors.Is(err, net.ErrClosed) || opened.Load() != 0 {
		t.Fatalf("closed device rebind: %v", err)
	}
}

func TestRebindRejectsDeviceCloseWithoutBindShutdown(t *testing.T) {
	var opened atomic.Int32
	b := New(func(conn.Bind, int) error { opened.Add(1); return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return newFakeBind() }
	d, _, _ := quietDevice(t, b)
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	d.Close()
	if b.IsStopped() {
		t.Fatal("device-only closure terminally stopped bind")
	}
	if err := d.Rebind(); !errors.Is(err, net.ErrClosed) || opened.Load() != 1 {
		t.Fatalf("device-only closure rebind: %v", err)
	}
}

type observedBind struct {
	conn.Bind
	warmups     atomic.Int32
	initiations atomic.Int32
	handshake   func([32]byte, int, bool, string)
}

func (b *observedBind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string) {
	if b.handshake != nil {
		b.handshake(peer, event, retry, endpoint)
	}
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
	var observationMu sync.Mutex
	var authenticated [][32]byte
	var serverAuthenticated [][32]byte
	bind.SetHandshakeObserver(func(peer [32]byte, event int, _ bool, endpoint string) {
		if event == HandshakeAuthenticated {
			observationMu.Lock()
			authenticated = append(authenticated, peer)
			observationMu.Unlock()
		}
	})
	bind.newBind = func() conn.Bind { return observed }
	client, clientTun, _ := quietDevice(t, bind)
	serverBind := &observedBind{Bind: conn.NewStdNetBind()}
	serverBind.handshake = func(peer [32]byte, event int, _ bool, endpoint string) {
		if event == HandshakeAuthenticated {
			observationMu.Lock()
			serverAuthenticated = append(serverAuthenticated, peer)
			observationMu.Unlock()
		}
	}
	server, serverTun, _ := quietDevice(t, serverBind)
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
	observationMu.Lock()
	defer observationMu.Unlock()
	if len(authenticated) != 1 || !bytes.Equal(authenticated[0][:], public[1]) {
		t.Fatal("actual authenticated completion lost peer or ordinary data fabricated authentication")
	}
	if len(serverAuthenticated) != 1 || !bytes.Equal(serverAuthenticated[0][:], public[0]) {
		t.Fatal("actual responder key confirmation lost peer or ordinary data fabricated authentication")
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
