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

type tunnlHandshakeObservation struct {
	peer     [32]byte
	event    int
	retry    bool
	endpoint string
}

type tunnlHandshakeBind struct {
	conn.Bind
	mu           sync.Mutex
	observations []tunnlHandshakeObservation
	sendError    error
}

func (b *tunnlHandshakeBind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string) {
	b.mu.Lock()
	b.observations = append(b.observations, tunnlHandshakeObservation{peer, event, retry, endpoint})
	b.mu.Unlock()
}

func (b *tunnlHandshakeBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	if b.sendError != nil {
		return b.sendError
	}
	return b.Bind.Send(packets, endpoint)
}

func (b *tunnlHandshakeBind) snapshot() []tunnlHandshakeObservation {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]tunnlHandshakeObservation(nil), b.observations...)
}

func tunnlHandshakePeer(t *testing.T, endpoint string, failSend bool) (*Peer, *tunnlHandshakeBind) {
	t.Helper()
	bind := &tunnlHandshakeBind{Bind: conn.NewStdNetBind()}
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

func TestTunnlHandshakeIdleAndLocalSendResult(t *testing.T) {
	for _, failSend := range []bool{false, true} {
		peer, bind := tunnlHandshakePeer(t, "127.0.0.1:51820", failSend)
		if len(bind.snapshot()) != 0 {
			t.Fatal("idle UP fabricated a handshake observation")
		}
		err := peer.SendHandshakeInitiation(false)
		if (err != nil) != failSend {
			t.Fatal("send result changed")
		}
		observations := bind.snapshot()
		result := tunnlHandshakeSent
		if failSend {
			result = tunnlHandshakeSendFailed
		}
		if len(observations) != 2 || observations[0].event != tunnlHandshakeAttempt || observations[1].event != result {
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

func TestTunnlHandshakeRetryBeforeActualExhaustion(t *testing.T) {
	peer, bind := tunnlHandshakePeer(t, "[::1]:51820", true)
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes)
	expiredRetransmitHandshake(peer)
	observations := bind.snapshot()
	if len(observations) != 2 || !observations[0].retry || observations[1].event != tunnlHandshakeSendFailed {
		t.Fatal("first tested retry fabricated exhaustion")
	}
	peer.timers.retransmitHandshake.Del()
	expiredRetransmitHandshake(peer)
	observations = bind.snapshot()
	if len(observations) != 3 || observations[2].event != tunnlHandshakeExhausted || !observations[2].retry {
		t.Fatal("actual exhausted branch did not emit distinct exhaustion")
	}
	if observations[2].endpoint != "[::1]:51820" {
		t.Fatal("IPv6 observation lost endpoint")
	}
}

func TestTunnlHandshakeEndpointFreeAndThrottledAttempt(t *testing.T) {
	peer, bind := tunnlHandshakePeer(t, "", false)
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
	if len(observations) != 2 || observations[0].endpoint != "" || observations[1].event != tunnlHandshakeSendFailed {
		t.Fatal("endpoint-free failure fabricated endpoint")
	}
	peer.timers.retransmitHandshake.Del()
}

func TestTunnlHandshakeDistinctPeersRetainIdentity(t *testing.T) {
	first, bind := tunnlHandshakePeer(t, "127.0.0.1:51820", true)
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
