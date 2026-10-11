/* SPDX-License-Identifier: Apache-2.0 */
package device

import (
	"errors"
	"sync"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

type vpn0wnHandshakeObservation struct {
	peer     [32]byte
	event    int
	retry    bool
	endpoint string
}

type vpn0wnHandshakeBind struct {
	conn.Bind
	mu           sync.Mutex
	observations []vpn0wnHandshakeObservation
	sendError    error
	sendCalls    int
	onObserve    func(vpn0wnHandshakeObservation)
}

func (b *vpn0wnHandshakeBind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string) {
	b.mu.Lock()
	observation := vpn0wnHandshakeObservation{peer, event, retry, endpoint}
	b.observations = append(b.observations, observation)
	b.mu.Unlock()
	if b.onObserve != nil {
		b.onObserve(observation)
	}
}

func (b *vpn0wnHandshakeBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	b.mu.Lock()
	b.sendCalls++
	b.mu.Unlock()
	if b.sendError != nil {
		return b.sendError
	}
	return b.Bind.Send(packets, endpoint)
}

func (b *vpn0wnHandshakeBind) snapshot() []vpn0wnHandshakeObservation {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]vpn0wnHandshakeObservation(nil), b.observations...)
}

func vpn0wnHandshakePeer(t *testing.T, endpoint string, failSend bool) (*Peer, *vpn0wnHandshakeBind) {
	t.Helper()
	bind := &vpn0wnHandshakeBind{Bind: conn.NewStdNetBind()}
	if failSend {
		bind.sendError = errors.New("synthetic socket failure")
	}
	tunnel := tuntest.NewChannelTUN()
	<-tunnel.TUN().Events()
	d := NewDevice(tunnel.TUN(), bind, NewLogger(LogLevelSilent, ""))
	t.Cleanup(d.Close)
	d.SetPrivateKey(NoisePrivateKey{1})
	peer, err := d.NewPeer(NoisePublicKey{9})
	if err != nil {
		t.Fatal(err)
	}
	if endpoint != "" {
		peer.endpoint.val, err = bind.ParseEndpoint(endpoint)
		if err != nil {
			t.Fatal(err)
		}
	}
	if err := d.Up(); err != nil {
		t.Fatal(err)
	}
	return peer, bind
}

func TestVpn0wnHandshakeIdleAndLocalSendResult(t *testing.T) {
	for _, failSend := range []bool{false, true} {
		peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", failSend)
		if len(bind.snapshot()) != 0 {
			t.Fatal("idle UP fabricated a handshake observation")
		}
		err := peer.SendHandshakeInitiation(false)
		if (err != nil) != failSend {
			t.Fatal("send result changed")
		}
		observations := bind.snapshot()
		result := vpn0wnHandshakeSent
		if failSend {
			result = vpn0wnHandshakeSendFailed
		}
		if len(observations) != 2 || observations[0].event != vpn0wnHandshakeAttempt || observations[1].event != result {
			t.Fatalf("attempt/result observations: %+v", observations)
		}
		for _, observation := range observations {
			if observation.peer != [32]byte(peer.handshake.remoteStatic) || observation.retry || observation.endpoint != "127.0.0.1:51820" {
				t.Fatal("observation lost peer/endpoint/retry identity")
			}
		}
		peer.timers.retransmitHandshake.Del()
	}
}

func TestVpn0wnHandshakeRetryBeforeActualExhaustion(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "[::1]:51820", true)
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes)
	expiredRetransmitHandshake(peer)
	observations := bind.snapshot()
	if len(observations) != 2 || !observations[0].retry || observations[1].event != vpn0wnHandshakeSendFailed {
		t.Fatal("first tested retry fabricated exhaustion")
	}
	peer.timers.retransmitHandshake.Del()
	expiredRetransmitHandshake(peer)
	observations = bind.snapshot()
	if len(observations) != 3 || observations[2].event != vpn0wnHandshakeExhausted || !observations[2].retry {
		t.Fatal("actual exhausted branch did not emit distinct exhaustion")
	}
	if observations[2].endpoint != "[::1]:51820" {
		t.Fatal("IPv6 observation lost endpoint")
	}
}

func TestVpn0wnHandshakeEndpointFreeAndThrottledAttempt(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "", false)
	if len(bind.snapshot()) != 0 {
		t.Fatal("endpoint-free peer fabricated a handshake")
	}
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now()
	peer.handshake.mutex.Unlock()
	if err := peer.SendHandshakeInitiation(false); err != nil || len(bind.snapshot()) != 0 {
		t.Fatal("throttled no-send fabricated an attempt")
	}
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
	peer.handshake.mutex.Unlock()
	if err := peer.SendHandshakeInitiation(false); err == nil {
		t.Fatal("endpoint-free actual send lost failure")
	}
	observations := bind.snapshot()
	if len(observations) != 2 || observations[0].endpoint != "" || observations[1].event != vpn0wnHandshakeSendFailed {
		t.Fatal("endpoint-free failure fabricated endpoint")
	}
	peer.timers.retransmitHandshake.Del()
}

func TestVpn0wnHandshakeDistinctPeersRetainIdentity(t *testing.T) {
	first, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", true)
	second, err := first.device.NewPeer(NoisePublicKey{10})
	if err != nil {
		t.Fatal(err)
	}
	second.endpoint.val, err = bind.ParseEndpoint("[::1]:51821")
	if err != nil {
		t.Fatal(err)
	}
	second.Start()
	for _, peer := range []*Peer{first, second} {
		if err := peer.SendHandshakeInitiation(false); err == nil {
			t.Fatal("synthetic send failure lost")
		}
		peer.timers.retransmitHandshake.Del()
	}
	observations := bind.snapshot()
	if len(observations) != 4 || observations[0].peer != [32]byte(first.handshake.remoteStatic) || observations[2].peer != [32]byte(second.handshake.remoteStatic) || observations[0].peer == observations[2].peer {
		t.Fatal("different peers lost native identity")
	}
	if observations[0].endpoint != "127.0.0.1:51820" || observations[2].endpoint != "[::1]:51821" {
		t.Fatal("different peers lost numeric endpoints")
	}
}

func TestVpn0wnHandshakeClosedDeviceCannotReportNoopAsSent(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	peer.device.Close()
	if err := peer.SendHandshakeInitiation(false); err != nil {
		t.Fatalf("closed-device upstream no-op changed: %v", err)
	}
	if observations := bind.snapshot(); len(observations) != 0 {
		t.Fatalf("closed device fabricated observations: %+v", observations)
	}
	bind.mu.Lock()
	defer bind.mu.Unlock()
	if bind.sendCalls != 0 {
		t.Fatal("closed-device no-op reached socket send")
	}
}

func TestVpn0wnHandshakeConcurrentCloseAfterAttemptCannotReportSent(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	attempted := make(chan struct{})
	released := make(chan struct{})
	bind.onObserve = func(observation vpn0wnHandshakeObservation) {
		if observation.event == vpn0wnHandshakeAttempt {
			close(attempted)
			<-released
		}
	}
	done := make(chan error, 1)
	go func() { done <- peer.SendHandshakeInitiation(false) }()
	select {
	case <-attempted:
	case <-time.After(time.Second):
		close(released)
		t.Fatal("actual initiation did not reach attempt observation")
	}
	peer.device.Close()
	close(released)
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("closed-device upstream no-op changed: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("initiation did not settle after actual concurrent close")
	}
	observations := bind.snapshot()
	if len(observations) != 1 || observations[0].event != vpn0wnHandshakeAttempt {
		t.Fatalf("concurrent close fabricated send result: %+v", observations)
	}
	bind.mu.Lock()
	defer bind.mu.Unlock()
	if bind.sendCalls != 0 {
		t.Fatal("closed-device no-op reached socket send")
	}
}
