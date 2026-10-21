/* SPDX-License-Identifier: Apache-2.0 */
package device

import (
	"errors"
	"net"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

type vpn0wnHandshakeObservation struct {
	peer     [32]byte
	event    int
	retry    bool
	endpoint string
	episode  uint64
}

type vpn0wnHandshakeBind struct {
	conn.Bind
	mu             sync.Mutex
	observations   []vpn0wnHandshakeObservation
	sendError      error
	sendCalls      int
	onObserve      func(vpn0wnHandshakeObservation)
	onOpen         func() error
	onClose        func()
	onSend         func() error
	openCalls      atomic.Int32
	lastInitiation []byte
	tunCloseCalls  atomic.Int32
}

type vpn0wnTrackedTUN struct {
	tun.Device
	closed *atomic.Int32
}

func (tunnel *vpn0wnTrackedTUN) Close() error {
	tunnel.closed.Add(1)
	return tunnel.Device.Close()
}

func (b *vpn0wnHandshakeBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.openCalls.Add(1)
	if b.onOpen != nil {
		if err := b.onOpen(); err != nil {
			return nil, 0, err
		}
	}
	return b.Bind.Open(port)
}

func (b *vpn0wnHandshakeBind) Close() error {
	if b.onClose != nil {
		b.onClose()
	}
	return b.Bind.Close()
}

func (b *vpn0wnHandshakeBind) ObserveHandshake(peer [32]byte, event int, retry bool, endpoint string, episode uint64) {
	b.mu.Lock()
	observation := vpn0wnHandshakeObservation{peer, event, retry, endpoint, episode}
	b.observations = append(b.observations, observation)
	b.mu.Unlock()
	if b.onObserve != nil {
		b.onObserve(observation)
	}
}

func (b *vpn0wnHandshakeBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	if b.onSend != nil {
		if err := b.onSend(); err != nil {
			return err
		}
	}
	b.mu.Lock()
	b.sendCalls++
	for _, packet := range packets {
		if len(packet) == MessageInitiationSize {
			b.lastInitiation = append([]byte(nil), packet...)
		}
	}
	err := b.sendError
	b.mu.Unlock()
	if err != nil {
		return err
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
	d := NewDevice(&vpn0wnTrackedTUN{Device: tunnel.TUN(), closed: &bind.tunCloseCalls}, bind, NewLogger(LogLevelSilent, ""))
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
	case <-time.After(5 * time.Second):
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
	case <-time.After(5 * time.Second):
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

func vpn0wnExhaustPeer(t *testing.T, peer *Peer, bind *vpn0wnHandshakeBind) uint64 {
	t.Helper()
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
	peer.handshake.mutex.Unlock()
	_ = peer.SendHandshakeInitiation(false)
	peer.timers.retransmitHandshake.Del()
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes + 1)
	expiredRetransmitHandshake(peer)
	observations := bind.snapshot()
	last := observations[len(observations)-1]
	if last.event != vpn0wnHandshakeExhausted || last.episode == 0 {
		t.Fatal("ordinary native cycle did not publish eligible exhaustion")
	}
	return last.episode
}

func TestVpn0wnHandshakeRecoveryClaimAndDuplicate(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	before, err := peer.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false })
	if result != RecoveryInitiationSkipped || bind.openCalls.Load() != 2 {
		t.Fatalf("throttled recovery: outcome=%d opens=%d", result, bind.openCalls.Load())
	}
	if result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete {
		t.Fatalf("duplicate claim outcome=%d", result)
	}
	after, err := peer.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	if before != after {
		t.Fatal("throttled shared rebind changed peer configuration")
	}
	if bind.tunCloseCalls.Load() != 0 {
		t.Fatal("recovery closed retained TUN")
	}
}

func TestVpn0wnHandshakeRecoveryRejectsBusyStaleEndpointAndNewCycle(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "[::1]:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	key := [32]byte(peer.handshake.remoteStatic)
	peer.device.ipcMutex.Lock()
	if result := peer.device.Recover(key, episode, "[::1]:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryBusy {
		t.Fatalf("busy outcome=%d", result)
	}
	peer.device.ipcMutex.Unlock()
	if result := peer.device.Recover(key, episode, "[::1]:51821", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete {
		t.Fatalf("changed endpoint outcome=%d", result)
	}
	newEpisode := vpn0wnExhaustPeer(t, peer, bind)
	if newEpisode == episode {
		t.Fatal("new cycle reused exhaustion token")
	}
	if result := peer.device.Recover(key, episode, "[::1]:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete || bind.openCalls.Load() != 1 {
		t.Fatal("old cycle mutated shared transport")
	}
}

func TestVpn0wnHandshakeHostnameRecoveryUpdatesOnlyClaimedPeerAndRetainsPortAndTun(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	other, err := peer.device.NewPeer(NoisePublicKey{10})
	if err != nil {
		t.Fatal(err)
	}
	other.endpoint.val, err = bind.ParseEndpoint("127.0.0.2:51821")
	if err != nil {
		t.Fatal(err)
	}
	port := peer.device.net.port
	result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "[::1]:51820",
		func() bool { return true }, func() bool { return false })
	if result != RecoveryInitiationSkipped || bind.openCalls.Load() != 2 {
		t.Fatalf("hostname recovery: outcome=%d opens=%d", result, bind.openCalls.Load())
	}
	peer.endpoint.Lock()
	endpoint := peer.endpoint.val.DstToString()
	peer.endpoint.Unlock()
	other.endpoint.Lock()
	unrelated := other.endpoint.val.DstToString()
	other.endpoint.Unlock()
	if endpoint != "[::1]:51820" || unrelated != "127.0.0.2:51821" || peer.device.net.port != port || bind.tunCloseCalls.Load() != 0 {
		t.Fatal("hostname recovery changed unrelated endpoint, local port or retained TUN")
	}
}

func TestVpn0wnHandshakeHostnameRecoveryRefusesStaleGenerationOrInvalidReplacementBeforeMutation(t *testing.T) {
	for _, test := range []struct {
		name, replacement string
		current bool
	}{
		{"generation", "127.0.0.2:51820", false},
		{"hostname", "vpn.example.invalid:51820", true},
		{"port", "127.0.0.2:51821", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
			episode := vpn0wnExhaustPeer(t, peer, bind)
			result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", test.replacement,
				func() bool { return test.current }, func() bool { return false })
			peer.endpoint.Lock()
			endpoint := peer.endpoint.val.DstToString()
			peer.endpoint.Unlock()
			if result != RecoveryObsolete || endpoint != "127.0.0.1:51820" || bind.openCalls.Load() != 1 {
				t.Fatal("obsolete resolution mutated endpoint or transport")
			}
		})
	}
}

func TestVpn0wnHandshakeRecoveryTokenDoesNotSurvivePeerReplacement(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	oldEpisode := vpn0wnExhaustPeer(t, peer, bind)
	key := peer.handshake.remoteStatic
	d := peer.device
	d.RemovePeer(key)
	replacement, err := d.NewPeer(key)
	if err != nil {
		t.Fatal(err)
	}
	replacement.endpoint.val, err = bind.ParseEndpoint("127.0.0.1:51820")
	if err != nil {
		t.Fatal(err)
	}
	replacement.Start()
	newEpisode := vpn0wnExhaustPeer(t, replacement, bind)
	if newEpisode == oldEpisode {
		t.Fatal("replacement peer reused token")
	}
	if result := d.Recover([32]byte(key), oldEpisode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete || bind.openCalls.Load() != 1 {
		t.Fatal("old peer trigger acquired replacement peer")
	}
}

func TestVpn0wnHandshakeRecoveryExhaustionPublishesAfterCleanup(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	peer.timers.sendKeepalive.Mod(time.Hour)
	peer.queue.staged <- peer.device.GetOutboundElementsContainer()
	bind.onObserve = func(observation vpn0wnHandshakeObservation) {
		if observation.event != vpn0wnHandshakeExhausted {
			return
		}
		if len(peer.queue.staged) != 0 || peer.timers.sendKeepalive.IsPending() || !peer.timers.zeroKeyMaterial.IsPending() {
			t.Error("exhaustion published before protocol cleanup settled")
		}
		if !peer.recovery.TryLock() {
			t.Error("observation callback held recovery lock")
			return
		}
		peer.recovery.Unlock()
	}
	_ = vpn0wnExhaustPeer(t, peer, bind)
}

func TestVpn0wnHandshakeRecoveryFailedOpenRemainsAdmittedWithoutInitiation(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	bind.mu.Lock()
	sends := bind.sendCalls
	bind.mu.Unlock()
	bind.onOpen = func() error { return errors.New("synthetic protection denial") }
	if result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryTransportFailed {
		t.Fatalf("failed transport outcome=%d", result)
	}
	bind.mu.Lock()
	defer bind.mu.Unlock()
	if bind.sendCalls != sends {
		t.Fatal("failed open requested an initiation")
	}
}

func TestVpn0wnHandshakeRecoveryDistinguishesStartedAndFailedSend(t *testing.T) {
	for _, failed := range []bool{false, true} {
		peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", failed)
		episode := vpn0wnExhaustPeer(t, peer, bind)
		peer.handshake.mutex.Lock()
		peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
		peer.handshake.mutex.Unlock()
		result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false })
		want := RecoveryInitiationStarted
		if failed {
			want = RecoveryInitiationSendFailed
		}
		if result != want {
			t.Fatalf("failed=%v outcome=%d want=%d", failed, result, want)
		}
		peer.timers.retransmitHandshake.Del()
	}
}

func TestVpn0wnHandshakeRecoveryCancellationWhileWaitingForStaticIdentity(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	var cancelled atomic.Bool
	var checks atomic.Int32
	preparing := make(chan struct{})
	checkCancellation := func() bool {
		value := cancelled.Load()
		if checks.Add(1) == 2 {
			close(preparing)
		}
		return value
	}
	peer.device.staticIdentity.Lock()
	result := make(chan int, 1)
	go func() {
		result <- peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, checkCancellation)
	}()
	select {
	case <-preparing:
	case <-time.After(5 * time.Second):
		t.Fatal("recovery did not reach initiation preparation")
	}
	cancelled.Store(true)
	peer.device.staticIdentity.Unlock()
	select {
	case outcome := <-result:
		if outcome != RecoveryInitiationSkipped {
			t.Fatalf("cancelled preparation outcome=%d", outcome)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancelled static-lock wait did not settle")
	}
}

func TestVpn0wnHandshakeRecoveryThrottledOrdinaryTrafficKeepsActiveCycle(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	if err := peer.SendHandshakeInitiation(false); err != nil {
		t.Fatal(err)
	}
	if err := peer.SendHandshakeInitiation(false); err != nil {
		t.Fatal(err)
	}
	peer.timers.retransmitHandshake.Del()
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes + 1)
	expiredRetransmitHandshake(peer)
	last := bind.snapshot()[len(bind.snapshot())-1]
	if last.episode == 0 {
		t.Fatal("throttled ordinary traffic discarded real pending cycle")
	}
	peer.timersHandshakeComplete()
	if err := peer.SendHandshakeInitiation(false); err != nil {
		t.Fatal(err)
	}
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes + 1)
	expiredRetransmitHandshake(peer)
	last = bind.snapshot()[len(bind.snapshot())-1]
	if last.episode != 0 {
		t.Fatal("throttled traffic reactivated authenticated cycle")
	}
}

func TestVpn0wnHandshakeRecoveryAcceptedResponseSuppressesOldExpiryBeforeComplete(t *testing.T) {
	local, localBind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	remote, remoteBind := vpn0wnHandshakePeer(t, "127.0.0.1:51821", false)
	remoteKey := NoisePrivateKey{2}
	if err := remote.device.SetPrivateKey(remoteKey); err != nil {
		t.Fatal(err)
	}
	localDevice, remoteDevice := local.device, remote.device
	localDevice.RemovePeer(local.handshake.remoteStatic)
	remoteDevice.RemovePeer(remote.handshake.remoteStatic)
	var err error
	local, err = localDevice.NewPeer(remoteKey.publicKey())
	if err != nil {
		t.Fatal(err)
	}
	local.endpoint.val, err = localBind.ParseEndpoint("127.0.0.1:51820")
	if err != nil {
		t.Fatal(err)
	}
	local.Start()
	localKey := NoisePrivateKey{1}
	remote, err = remoteDevice.NewPeer(localKey.publicKey())
	if err != nil {
		t.Fatal(err)
	}
	remote.endpoint.val, err = remoteBind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	remote.Start()
	episode := vpn0wnExhaustPeer(t, local, localBind)
	localBind.mu.Lock()
	packet := append([]byte(nil), localBind.lastInitiation...)
	localBind.mu.Unlock()
	var initiation MessageInitiation
	if err := initiation.unmarshal(packet); err != nil {
		t.Fatal(err)
	}
	if accepted := remoteDevice.ConsumeMessageInitiation(&initiation); accepted != remote {
		t.Fatal("valid initiation not accepted")
	}
	response, err := remoteDevice.CreateMessageResponse(remote)
	if err != nil {
		t.Fatal(err)
	}
	if accepted := localDevice.ConsumeMessageResponse(response); accepted != local {
		t.Fatal("valid response not accepted")
	}
	if local.timers.handshakeAttempts.Load() <= MaxTimerHandshakes {
		t.Fatal("fixture lost pre-completion timer window")
	}
	expiredRetransmitHandshake(local)
	observations := localBind.snapshot()
	if observations[len(observations)-1].episode != 0 {
		t.Fatal("old expiry minted a token after accepted response")
	}
	if result := localDevice.Recover([32]byte(local.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete || localBind.openCalls.Load() != 1 {
		t.Fatal("authenticated progress allowed old recovery mutation")
	}
}

func TestVpn0wnHandshakeRecoveryResponderConfirmationSuppressesOldExpiry(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	keypair := new(Keypair)
	peer.keypairs.next.Store(keypair)
	if !peer.ReceivedWithKeypair(keypair) {
		t.Fatal("responder confirmation not accepted")
	}
	expiredRetransmitHandshake(peer)
	observations := bind.snapshot()
	if observations[len(observations)-1].episode != 0 {
		t.Fatal("old expiry minted token after responder confirmation")
	}
	if result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false }); result != RecoveryObsolete {
		t.Fatal("confirmed peer allowed old recovery")
	}
}

func TestVpn0wnHandshakeRecoveryPendingStaticWriterDoesNotDeadlock(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
	peer.handshake.mutex.Unlock()
	var checks atomic.Int32
	readerHeld := make(chan struct{})
	continueRecovery := make(chan struct{})
	checkCancellation := func() bool {
		if checks.Add(1) == 3 {
			close(readerHeld)
			<-continueRecovery
		}
		return false
	}
	recovered := make(chan int, 1)
	go func() {
		recovered <- peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, checkCancellation)
	}()
	select {
	case <-readerHeld:
	case <-time.After(5 * time.Second):
		t.Fatal("preparation did not hold static reader")
	}
	written := make(chan error, 1)
	go func() { written <- peer.device.SetPrivateKey(NoisePrivateKey{1}) }()
	deadline := time.Now().Add(5 * time.Second)
	for peer.device.staticIdentity.TryRLock() {
		peer.device.staticIdentity.RUnlock()
		if time.Now().After(deadline) {
			t.Fatal("static writer did not queue")
		}
		runtime.Gosched()
	}
	close(continueRecovery)
	select {
	case outcome := <-recovered:
		if outcome != RecoveryInitiationStarted {
			t.Fatalf("pending writer recovery outcome=%d", outcome)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("recovery deadlocked behind pending static writer")
	}
	select {
	case err := <-written:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("static writer did not settle")
	}
	peer.timers.retransmitHandshake.Del()
}

func TestVpn0wnHandshakeRecoveryCancelsCapturedSendBeforeDown(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	episode := vpn0wnExhaustPeer(t, peer, bind)
	oldSendEntered := make(chan struct{})
	oldClosed := make(chan struct{})
	replacementOpening := make(chan struct{})
	releaseReplacement := make(chan struct{})
	var closed sync.Once
	bind.onClose = func() { closed.Do(func() { close(oldClosed) }) }
	bind.onSend = func() error {
		close(oldSendEntered)
		<-oldClosed
		return net.ErrClosed
	}
	bind.onOpen = func() error { close(replacementOpening); <-releaseReplacement; return nil }
	sent := make(chan error, 1)
	go func() { sent <- peer.SendBuffers([][]byte{{4}}) }()
	select {
	case <-oldSendEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("old send did not enter")
	}
	recovered := make(chan int, 1)
	go func() {
		recovered <- peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false })
	}()
	select {
	case <-replacementOpening:
	case <-time.After(5 * time.Second):
		t.Fatal("replacement waited for old network read lock")
	}
	select {
	case <-oldClosed:
	default:
		t.Fatal("replacement opened before cancelling old sockets")
	}
	down := make(chan error, 1)
	go func() { down <- peer.device.Down() }()
	deadline := time.Now().Add(5 * time.Second)
	for peer.device.isUp() {
		if time.Now().After(deadline) {
			t.Fatal("DOWN did not retire native UP")
		}
		runtime.Gosched()
	}
	close(releaseReplacement)
	select {
	case err := <-sent:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatal("captured send did not cancel")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("captured send stuck")
	}
	select {
	case outcome := <-recovered:
		if outcome != RecoveryTransportFailed {
			t.Fatalf("DOWN recovery outcome=%d", outcome)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("recovery did not settle")
	}
	select {
	case err := <-down:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("DOWN did not settle")
	}
	observations := bind.snapshot()
	if observations[len(observations)-1].event != vpn0wnHandshakeExhausted {
		t.Fatal("DOWN recovery started an initiation")
	}
	if bind.tunCloseCalls.Load() != 0 {
		t.Fatal("DOWN recovery closed retained TUN")
	}
}

func TestVpn0wnHandshakeRecoveryPreservesMixedPeersOnSharedTransport(t *testing.T) {
	peer, bind := vpn0wnHandshakePeer(t, "127.0.0.1:51820", false)
	healthy, err := peer.device.NewPeer(NoisePublicKey{8})
	if err != nil {
		t.Fatal(err)
	}
	healthy.endpoint.val, err = bind.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	healthy.Start()
	endpointFree, err := peer.device.NewPeer(NoisePublicKey{7})
	if err != nil {
		t.Fatal(err)
	}
	endpointFree.Start()
	healthy.timersHandshakeComplete()
	episode := vpn0wnExhaustPeer(t, peer, bind)
	before, err := peer.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	bind.mu.Lock()
	observations := len(bind.observations)
	bind.mu.Unlock()
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
	peer.handshake.mutex.Unlock()
	result := peer.device.Recover([32]byte(peer.handshake.remoteStatic), episode, "127.0.0.1:51820", "", func() bool { return true }, func() bool { return false })
	if result != RecoveryInitiationStarted {
		t.Fatalf("mixed-peer recovery outcome=%d", result)
	}
	peer.timers.retransmitHandshake.Del()
	for _, observation := range bind.snapshot()[observations:] {
		if observation.peer != [32]byte(peer.handshake.remoteStatic) {
			t.Fatal("recovery requested another peer's initiation")
		}
	}
	after, err := peer.device.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	if vpn0wnRecoveryConfiguration(before) != vpn0wnRecoveryConfiguration(after) || endpointFree.endpoint.val != nil || bind.tunCloseCalls.Load() != 0 {
		t.Fatal("recovery changed mixed peer configuration or retained TUN")
	}
}

func vpn0wnRecoveryConfiguration(config string) string {
	var lines []string
	for _, line := range strings.Split(config, "\n") {
		if !strings.HasPrefix(line, "tx_bytes=") {
			lines = append(lines, line)
		}
	}
	blocks := strings.Split(strings.TrimSuffix(strings.Join(lines, "\n"), "\n"), "\npublic_key=")
	sort.Strings(blocks[1:])
	return strings.Join(blocks, "\npublic_key=")
}

func TestVpn0wnHandshakeRecoveryConfigurationComparison(t *testing.T) {
	header := "private_key=synthetic\nlisten_port=35000\n"
	first := "public_key=aaa\npreshared_key=first\nendpoint=127.0.0.1:51820\nallowed_ip=10.7.0.1/32\nrx_bytes=0\ntx_bytes=1\n"
	second := "public_key=bbb\npreshared_key=second\nendpoint=127.0.0.1:51821\nallowed_ip=10.7.0.2/32\nrx_bytes=0\ntx_bytes=2\n"
	original := header + first + second
	permuted := header + second + first
	if vpn0wnRecoveryConfiguration(original) != vpn0wnRecoveryConfiguration(permuted) {
		t.Fatal("unordered peer blocks changed configuration comparison")
	}
	counters := strings.NewReplacer("tx_bytes=1", "tx_bytes=3", "tx_bytes=2", "tx_bytes=4").Replace(permuted)
	if vpn0wnRecoveryConfiguration(original) != vpn0wnRecoveryConfiguration(counters) {
		t.Fatal("allowed transmitted-byte changes changed configuration comparison")
	}
	changes := map[string]string{
		"endpoint association":   strings.NewReplacer("127.0.0.1:51820", "127.0.0.1:51821", "127.0.0.1:51821", "127.0.0.1:51820").Replace(permuted),
		"PSK association":        strings.NewReplacer("preshared_key=first", "preshared_key=second", "preshared_key=second", "preshared_key=first").Replace(permuted),
		"allowed IP association": strings.NewReplacer("10.7.0.1/32", "10.7.0.2/32", "10.7.0.2/32", "10.7.0.1/32").Replace(permuted),
		"removed peer":           header + first,
		"changed device port":    strings.Replace(permuted, "listen_port=35000", "listen_port=35001", 1),
		"received-byte change":   strings.Replace(permuted, "rx_bytes=0", "rx_bytes=1", 1),
	}
	for name, changed := range changes {
		t.Run(name, func(t *testing.T) {
			if vpn0wnRecoveryConfiguration(original) == vpn0wnRecoveryConfiguration(changed) {
				t.Fatal("meaningful peer/device change was ignored")
			}
		})
	}
}
