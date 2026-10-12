/* SPDX-License-Identifier: Apache-2.0 */

package main

const recoveryPeerState = `recovery struct {
		sync.Mutex
		generation uint64
		exhausted uint64
		claimed bool
		activeCycle bool
	}
	`

const recoveryProgressSource = `func (peer *Peer) advanceRecoveryCycle() {
	peer.recovery.generation = peer.device.recoverySequence.Add(1)
	peer.recovery.exhausted = 0
	peer.recovery.claimed = false
	peer.recovery.activeCycle = false
}

`

const recoveryDeviceSource = `const (
	RecoveryBusy = iota + 1
	RecoveryObsolete
	RecoveryUnavailable
	RecoveryTransportFailed
	RecoveryInitiationStarted
	RecoveryInitiationSkipped
	RecoveryInitiationFailed
	RecoveryInitiationSendFailed
)

func (device *Device) Recover(key [32]byte, episode uint64, endpoint string, cancelled func() bool) int {
	if !device.ipcMutex.TryLock() {
		return RecoveryBusy
	}
	defer device.ipcMutex.Unlock()
	if !device.isUp() || cancelled() {
		return RecoveryUnavailable
	}
	peer := device.LookupPeer(NoisePublicKey(key))
	if peer == nil || episode == 0 {
		return RecoveryObsolete
	}
	if !peer.recovery.TryLock() {
		return RecoveryBusy
	}
	peer.endpoint.Lock()
	currentEndpoint := ""
	if peer.endpoint.val != nil {
		currentEndpoint = peer.endpoint.val.DstToString()
	}
	peer.endpoint.Unlock()
	_, numericError := netip.ParseAddrPort(currentEndpoint)
	eligible := peer.isRunning.Load() && peer.recovery.exhausted == episode && !peer.recovery.claimed &&
		numericError == nil && currentEndpoint == endpoint
	if eligible {
		peer.recovery.claimed = true
	}
	peer.recovery.Unlock()
	if !eligible {
		return RecoveryObsolete
	}
	if err := device.rebindLocked(); err != nil {
		return RecoveryTransportFailed
	}
	if cancelled() {
		return RecoveryInitiationSkipped
	}
	started, err := peer.sendHandshakeInitiation(false, episode, cancelled)
	if err != nil {
		if started {
			return RecoveryInitiationSendFailed
		}
		return RecoveryInitiationFailed
	}
	if !started {
		return RecoveryInitiationSkipped
	}
	return RecoveryInitiationStarted
}

`
