/* SPDX-License-Identifier: Apache-2.0 */
package device

import (
	"errors"
	"time"
)

// Host-only overlay fixture: exercise real owners without waiting through protocol retry timing.
func (device *Device) ExhaustPeerForTest(key [32]byte) (uint64, error) {
	peer := device.LookupPeer(NoisePublicKey(key))
	if peer == nil {
		return 0, errors.New("missing fixture peer")
	}
	peer.handshake.mutex.Lock()
	peer.handshake.lastSentHandshake = time.Now().Add(-2 * RekeyTimeout)
	peer.handshake.mutex.Unlock()
	if err := peer.SendHandshakeInitiation(false); err != nil {
		return 0, err
	}
	peer.timers.retransmitHandshake.Del()
	peer.timers.handshakeAttempts.Store(MaxTimerHandshakes + 1)
	expiredRetransmitHandshake(peer)
	peer.recovery.Lock()
	defer peer.recovery.Unlock()
	return peer.recovery.exhausted, nil
}
