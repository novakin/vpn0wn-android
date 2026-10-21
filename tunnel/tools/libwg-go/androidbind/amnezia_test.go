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
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
	awgdevice "github.com/amnezia-vpn/amneziawg-go/v3/device"
	awgtun "github.com/amnezia-vpn/amneziawg-go/v3/tun"
	awgtuntest "github.com/amnezia-vpn/amneziawg-go/v3/tun/tuntest"
	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
)

type amneziaPacketBind struct {
	conn.Bind
	mu      sync.Mutex
	packets [][]byte
}

type amneziaBatchBind struct{ *fakeBind }

func (b amneziaBatchBind) BatchSize() int { return 128 }

type amneziaReadBarrier struct {
	awgtun.Device
	started chan struct{}
	once    sync.Once
}

func (b *amneziaReadBarrier) Read(packets [][]byte, sizes []int, offset int) (int, error) {
	b.once.Do(func() { close(b.started) })
	return b.Device.Read(packets, sizes, offset)
}

func (b *amneziaPacketBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	b.mu.Lock()
	for _, packet := range packets {
		b.packets = append(b.packets, bytes.Clone(packet))
	}
	b.mu.Unlock()
	return b.Bind.Send(packets, endpoint)
}

func TestAmneziaNativeHandshakeTrafficAndRetainedPortRebind(t *testing.T) {
	observed := &amneziaPacketBind{Bind: conn.NewStdNetBind()}
	var protection atomic.Int32
	var authenticated atomic.Int32
	owner := New(func(conn.Bind, int) error { protection.Add(1); return nil }, func() bool { return true })
	owner.newBind = func() conn.Bind { return observed }
	owner.SetHandshakeObserver(func(_ [32]byte, event int, _ bool, _ string, _ uint64) {
		if event == HandshakeAuthenticated {
			authenticated.Add(1)
		}
	})
	var peers [2]*awgdevice.Device
	var tunnels [2]*awgtuntest.ChannelTUN
	var private [2][32]byte
	var public [2][]byte
	for i := range peers {
		tunnels[i] = awgtuntest.NewChannelTUN()
		<-tunnels[i].TUN().Events()
		var bind awgconn.Bind = awgconn.NewStdNetBind()
		if i == 0 {
			bind = Amnezia{Bind: owner}
		}
		barrier := &amneziaReadBarrier{Device: tunnels[i].TUN(), started: make(chan struct{})}
		peers[i] = awgdevice.NewDevice(barrier, bind, awgdevice.NewLogger(awgdevice.LogLevelSilent, ""))
		peer := peers[i]
		t.Cleanup(func() { peer.Close() })
		select {
		case <-barrier.started:
		case <-time.After(5 * time.Second):
			t.Fatal("AWG TUN read did not start before configuration")
		}
		if _, err := rand.Read(private[i][:]); err != nil {
			t.Fatal(err)
		}
		var err error
		public[i], err = curve25519.X25519(private[i][:], curve25519.Basepoint)
		if err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() { owner.Shutdown() })
	for i, peer := range peers {
		configuration := fmt.Sprintf("private_key=%s\nlisten_port=0\ni1=<b aabb>\njc=2\njmin=8\njmax=8\ns1=12\ns2=12\ns3=12\ns4=12\nh1=101\nh2=102\nh3=103\nh4=104\npublic_key=%s\nallowed_ip=10.0.0.%d/32\n", hex.EncodeToString(private[i][:]), hex.EncodeToString(public[i^1]), (i^1)+1)
		if err := peer.IpcSet(configuration); err != nil {
			t.Fatal("synthetic AWG configuration rejected", err)
		}
		if err := peer.Up(); err != nil {
			t.Fatal(err)
		}
	}
	port := configPort(t, peers[0])
	if err := peers[0].IpcSet(fmt.Sprintf("listen_port=%d\npublic_key=%s\nendpoint=127.0.0.1:%d\n", port, hex.EncodeToString(public[1]), configPort(t, peers[1]))); err != nil {
		t.Fatal(err)
	}
	for round, sender := range []int{0, 1, 0} {
		if round == 2 {
			if err := peers[0].Rebind(); err != nil {
				t.Fatal(err)
			}
			if configPort(t, peers[0]) != port {
				t.Fatal("AWG rebind changed local port")
			}
		}
		packet := awgtuntest.Ping(netip.MustParseAddr(fmt.Sprintf("10.0.0.%d", (sender^1)+1)), netip.MustParseAddr(fmt.Sprintf("10.0.0.%d", sender+1)))
		select {
		case tunnels[sender].Outbound <- packet:
		case <-time.After(5 * time.Second):
			t.Fatal("AWG TUN send stalled")
		}
		select {
		case received := <-tunnels[sender^1].Inbound:
			if !bytes.Equal(received, packet) {
				t.Fatal("AWG authenticated packet changed")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("AWG authenticated traffic stalled")
		}
	}
	configuration, err := peers[0].IpcGet()
	if err != nil || !strings.Contains(configuration, "disable_cookies=0\n") {
		t.Fatal("AWG cookies not enabled by default")
	}
	if authenticated.Load() == 0 || protection.Load() < 2 {
		t.Fatal("AWG authentication/rebind protection not observed")
	}
	observed.mu.Lock()
	defer observed.mu.Unlock()
	if len(observed.packets) < 4 || !bytes.Equal(observed.packets[0], []byte{0xaa, 0xbb}) || len(observed.packets[1]) != 8 || len(observed.packets[2]) != 8 || len(observed.packets[3]) != 160 {
		t.Fatal("AWG I/J/initiation packet order changed")
	}
	for _, packet := range observed.packets {
		if len(packet) == 1 {
			t.Fatal("ordinary WireGuard warm-up added to AWG")
		}
	}
}

func TestAmneziaPreservesPacketTrainWithoutWireGuardWarmup(t *testing.T) {
	base := newFakeBind()
	protected := 0
	owner := New(func(conn.Bind, int) error { protected++; return nil }, func() bool { return true })
	owner.newBind = func() conn.Bind { return base }
	bind := Amnezia{Bind: owner}
	bind.ListenPortChanged(51820)
	receivers, port, err := bind.Open(51820)
	if err != nil || port != 51820 || protected != 1 || len(receivers) != 2 {
		t.Fatalf("protected activation failed: port=%d protection=%d receivers=%d error=%v", port, protected, len(receivers), err)
	}
	t.Cleanup(func() { owner.Shutdown() })
	endpoint, err := bind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	packets := [][]byte{{0x61, 0x62}, {0x63}, initiation()}
	if err := bind.Send(packets, endpoint); err != nil {
		t.Fatal(err)
	}
	actual := base.sent()
	if len(actual) != len(packets) {
		t.Fatalf("unexpected decoration: %d packets", len(actual))
	}
	for i := range packets {
		if !bytes.Equal(actual[i], packets[i]) {
			t.Fatalf("packet %d changed", i)
		}
	}
}

func TestAmneziaStopsBeforeInitiationWhenServiceEndsDuringTrain(t *testing.T) {
	base := newFakeBind()
	var active atomic.Bool
	active.Store(true)
	owner := New(func(conn.Bind, int) error { return nil }, active.Load)
	owner.newBind = func() conn.Bind { return amneziaBatchBind{base} }
	bind := Amnezia{Bind: owner}
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { owner.Shutdown() })
	base.onSend = func([][]byte) error { active.Store(false); return nil }
	endpoint, err := bind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	if err := bind.SendInitiation([][]byte{{0x61}, {0x62}, initiation()}, endpoint); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("obsolete train not refused: %v", err)
	}
	if len(base.sent()) != 2 {
		t.Fatal("initiation escaped after service retirement")
	}
}

func TestAmneziaInitiationNeverMovesToReplacementGeneration(t *testing.T) {
	base, replacement := newFakeBind(), newFakeBind()
	owner := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	owner.newBind = func() conn.Bind { return amneziaBatchBind{base} }
	bind := Amnezia{Bind: owner}
	if _, _, err := bind.Open(51820); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { owner.Shutdown() })
	base.onSend = func([][]byte) error {
		if err := bind.Close(); err != nil {
			return err
		}
		owner.newBind = func() conn.Bind { return amneziaBatchBind{replacement} }
		_, _, err := bind.Open(51820)
		return err
	}
	endpoint, err := bind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	if err := bind.SendInitiation([][]byte{{0x61}, initiation()}, endpoint); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("replaced generation accepted initiation: %v", err)
	}
	if len(base.sent()) != 1 || len(replacement.sent()) != 0 {
		t.Fatal("initiation escaped generation boundary")
	}
}

func TestAmneziaEngineUsesPreludeCancellationBoundary(t *testing.T) {
	base := newFakeBind()
	var active atomic.Bool
	active.Store(true)
	owner := New(func(conn.Bind, int) error { return nil }, active.Load)
	owner.newBind = func() conn.Bind { return amneziaBatchBind{base} }
	tunnel := awgtuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	engine := awgdevice.NewDevice(tunnel.TUN(), Amnezia{Bind: owner}, awgdevice.NewLogger(awgdevice.LogLevelSilent, ""))
	t.Cleanup(func() { owner.Shutdown(); engine.Close() })
	localPrivate, remotePrivate := [32]byte{8}, [32]byte{16}
	remotePublic, err := curve25519.X25519(remotePrivate[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	configuration := fmt.Sprintf("private_key=%s\nlisten_port=51820\ni1=<b aabb>\npublic_key=%s\nendpoint=127.0.0.1:51821\n", hex.EncodeToString(localPrivate[:]), hex.EncodeToString(remotePublic))
	if err := engine.IpcSet(configuration); err != nil {
		t.Fatal(err)
	}
	if err := engine.Up(); err != nil {
		t.Fatal(err)
	}
	base.onSend = func([][]byte) error { active.Store(false); return nil }
	var key awgdevice.NoisePublicKey
	copy(key[:], remotePublic)
	peer := engine.LookupPeer(key)
	if peer == nil {
		t.Fatal("synthetic AWG peer missing")
	}
	if err := peer.SendHandshakeInitiation(false); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("native initiation ignored retired service: %v", err)
	}
	packets := base.sent()
	if len(packets) != 1 || !bytes.Equal(packets[0], []byte{0xaa, 0xbb}) {
		t.Fatal("native AWG initiation escaped prelude cancellation")
	}
}

func TestAmneziaOrdinaryTransportRetainsNativeBatching(t *testing.T) {
	base := newFakeBind()
	owner := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	owner.newBind = func() conn.Bind { return amneziaBatchBind{base} }
	bind := Amnezia{Bind: owner}
	if _, _, err := bind.Open(0); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { owner.Shutdown() })
	calls := 0
	base.onSend = func(packets [][]byte) error {
		calls++
		if len(packets) != 3 {
			t.Fatal("ordinary data batch split at final packet")
		}
		return nil
	}
	endpoint, err := bind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	if err := bind.Send([][]byte{{1, 2}, {3, 4}, {5, 6}}, endpoint); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatal("ordinary data batch did not use one native send")
	}
}

func TestAmneziaProtectionFailureDoesNotPublishReceivers(t *testing.T) {
	base := newFakeBind()
	owner := New(func(conn.Bind, int) error { return errors.New("protection denied") }, func() bool { return true })
	owner.newBind = func() conn.Bind { return base }
	bind := Amnezia{Bind: owner}
	receivers, _, err := bind.Open(0)
	if err == nil || len(receivers) != 0 {
		t.Fatal("unprotected socket published")
	}
	select {
	case <-base.closed:
	default:
		t.Fatal("denied socket leaked")
	}
	owner.Shutdown()
}
