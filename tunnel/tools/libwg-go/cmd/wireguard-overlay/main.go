/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const version = "v0.0.0-20250521234502-f333402bd9cb"

func main() {
	if len(os.Args) < 2 || len(os.Args) > 3 || (len(os.Args) == 3 && os.Args[2] != "host") {
		fmt.Fprintln(os.Stderr, "usage: wireguard-overlay OUTPUT_DIRECTORY [host]")
		os.Exit(1)
	}
	if err := generate(os.Args[1], len(os.Args) == 3); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func generate(output string, host bool) error {
	info, err := exec.Command("go", "list", "-m", "-json", "golang.zx2c4.com/wireguard").Output()
	if err != nil {
		return fmt.Errorf("locate pinned wireguard module: %w", err)
	}
	var module struct {
		Version string
		Dir     string
		Replace json.RawMessage
	}
	if err := json.Unmarshal(info, &module); err != nil {
		return err
	}
	if module.Version != version || len(module.Replace) != 0 {
		return fmt.Errorf("wireguard overlay requires %s", version)
	}
	if module.Dir == "" {
		download, err := exec.Command("go", "mod", "download", "-json", "golang.zx2c4.com/wireguard@"+version).Output()
		if err != nil {
			return fmt.Errorf("download pinned WireGuard source: %w", err)
		}
		if err := json.Unmarshal(download, &module); err != nil {
			return err
		}
	}
	if module.Dir == "" {
		return fmt.Errorf("pinned WireGuard source directory unavailable")
	}
	output, err = filepath.Abs(output)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(output, 0755); err != nil {
		return err
	}
	// Go rejects overlays beneath its immutable module cache.
	staged := filepath.Join(output, "wireguard")
	if err := os.RemoveAll(staged); err != nil {
		return err
	}
	if err := os.CopyFS(staged, os.DirFS(module.Dir)); err != nil {
		return err
	}
	module.Dir = staged
	mainMod, err := exec.Command("go", "env", "GOMOD").Output()
	if err != nil {
		return err
	}
	moduleRoot := filepath.Dir(strings.TrimSpace(string(mainMod)))
	for _, name := range []string{"go.mod", "go.sum"} {
		data, err := os.ReadFile(filepath.Join(moduleRoot, name))
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(output, name), data, 0644); err != nil {
			return err
		}
	}
	relative, err := filepath.Rel(moduleRoot, staged)
	if err != nil {
		return err
	}
	if err := exec.Command("go", "mod", "edit", "-modfile="+filepath.Join(output, "go.mod"), "-replace=golang.zx2c4.com/wireguard=./"+filepath.ToSlash(relative)).Run(); err != nil {
		return err
	}
	replacements := make(map[string]string)
	for _, file := range []string{"device/device.go", "device/peer.go", "device/noise-protocol.go", "device/uapi.go", "device/send.go", "device/timers.go", "conn/boundif_android.go"} {
		name := filepath.Base(file)
		sourcePath := filepath.Join(module.Dir, file)
		source, err := os.ReadFile(sourcePath)
		if err != nil {
			return err
		}
		patched, err := patch(name, string(source))
		if err != nil {
			return err
		}
		patchedPath := filepath.Join(output, name)
		if err := os.WriteFile(patchedPath, []byte(patched), 0644); err != nil {
			return err
		}
		replacements[sourcePath] = patchedPath
	}
	if host {
		descriptors, err := os.ReadFile(filepath.Join(output, "boundif_android.go"))
		if err != nil {
			return err
		}
		hostPath := filepath.Join(module.Dir, "conn", "default.go")
		source, err := os.ReadFile(hostPath)
		if err != nil {
			return err
		}
		packageLine := "package conn\n"
		header := "package conn\n\nimport \"syscall\"\n"
		if strings.Count(string(source), packageLine) != 1 {
			return fmt.Errorf("unexpected host bind package layout")
		}
		// Expose the Android-only descriptor methods to host fixtures.
		alias := strings.Replace(string(source), packageLine, header, 1) + "\n" + strings.Replace(string(descriptors), header, "", 1)
		aliasPath := filepath.Join(output, "default_host.go")
		if err := os.WriteFile(aliasPath, []byte(alias), 0644); err != nil {
			return err
		}
		replacements[hostPath] = aliasPath
		for upstream, fixture := range map[string]string{"vpn0wn_descriptors_test.go": "boundif_test.go", "vpn0wn_protection_test.go": "protection_test.go"} {
			fixturePath, err := filepath.Abs(filepath.Join("tests", fixture))
			if err != nil {
				return err
			}
			replacements[filepath.Join(module.Dir, "conn", upstream)] = fixturePath
		}
		fixturePath, err := filepath.Abs(filepath.Join("tests", "handshake_test.go"))
		if err != nil {
			return err
		}
		replacements[filepath.Join(module.Dir, "device", "vpn0wn_handshake_test.go")] = fixturePath
		recoveryFixture, err := filepath.Abs(filepath.Join("tests", "recovery_fixture.go"))
		if err != nil {
			return err
		}
		replacements[filepath.Join(module.Dir, "device", "vpn0wn_recovery_fixture.go")] = recoveryFixture
	}
	data, err := json.Marshal(struct{ Replace map[string]string }{replacements})
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(output, "overlay.json"), data, 0644)
}

func patch(name, source string) (string, error) {
	var edits [][2]string
	switch name {
	case "device.go":
		edits = [][2]string{
			{"recvFns, netc.port, err = netc.bind.Open(netc.port)", "var actualPort uint16\n\trecvFns, actualPort, err = netc.bind.Open(netc.port)"},
			{"\t\tnetc.port = 0\n", ""},
			{"if err != nil {\n\t\t\treturn err\n\t\t}\n\t}\n\n\t// clear cached source addresses\n\tdevice.peers.RLock()", "if err != nil {\n\t\t\tcloseBindLocked(device)\n\t\t\treturn err\n\t\t}\n\t}\n\tnetc.port = actualPort\n\n\t// clear cached source addresses\n\tdevice.peers.RLock()"},
			{"\t\"runtime\"\n", "\t\"net\"\n\t\"net/netip\"\n\t\"runtime\"\n\t\"syscall\"\n"},
			{"func (device *Device) BindUpdate() error {", retainedRebindSource + recoveryDeviceSource + "\nfunc (device *Device) BindUpdate() error {"},
			{"type Device struct {", "type Device struct {\n\trecoverySequence atomic.Uint64"},
		}
	case "peer.go":
		edits = [][2]string{
			{"\tendpoint struct {", "\t" + recoveryPeerState + "endpoint struct {"},
			{"func (peer *Peer) SetEndpointFromPacket(endpoint conn.Endpoint) {", recoveryProgressSource + "func (peer *Peer) SetEndpointFromPacket(endpoint conn.Endpoint) {\n\tpeer.recovery.Lock()\n\tdefer peer.recovery.Unlock()"},
			{"peer.endpoint.val = endpoint\n}", "peer.endpoint.val = endpoint\n\tpeer.advanceRecoveryCycle()\n}"},
		}
	case "noise-protocol.go":
		edits = [][2]string{
			{"func (device *Device) CreateMessageInitiation(peer *Peer) (*MessageInitiation, error) {\n\tdevice.staticIdentity.RLock()\n\tdefer device.staticIdentity.RUnlock()", "func (device *Device) CreateMessageInitiation(peer *Peer) (*MessageInitiation, error) {\n\tdevice.staticIdentity.RLock()\n\tdefer device.staticIdentity.RUnlock()\n\treturn device.createMessageInitiationLocked(peer)\n}\n\nfunc (device *Device) createMessageInitiationLocked(peer *Peer) (*MessageInitiation, error) {"},
			{"handshake := lookup.handshake", "handshake := lookup.handshake\n\tpeer := lookup.peer"},
			{"// update handshake state\n\n\thandshake.mutex.Lock()", "// update handshake state\n\n\tpeer.recovery.Lock()\n\thandshake.mutex.Lock()"},
			{"handshake.state = handshakeInitiationConsumed\n\n\thandshake.mutex.Unlock()", "handshake.state = handshakeInitiationConsumed\n\tpeer.advanceRecoveryCycle()\n\n\thandshake.mutex.Unlock()\n\tpeer.recovery.Unlock()"},
			{"handshake.state = handshakeResponseConsumed\n\n\thandshake.mutex.Unlock()", "handshake.state = handshakeResponseConsumed\n\tpeer.advanceRecoveryCycle()\n\n\thandshake.mutex.Unlock()\n\tpeer.recovery.Unlock()"},
			{"func (peer *Peer) ReceivedWithKeypair(receivedKeypair *Keypair) bool {", "func (peer *Peer) ReceivedWithKeypair(receivedKeypair *Keypair) bool {\n\tpeer.recovery.Lock()\n\tdefer peer.recovery.Unlock()"},
			{"keypairs.next.Store(nil)\n\treturn true", "keypairs.next.Store(nil)\n\tpeer.advanceRecoveryCycle()\n\treturn true"},
		}
	case "uapi.go":
		edits = [][2]string{
			{"device.net.port = uint16(port)", "device.net.port = uint16(port)\n\t\tif bind, ok := device.net.bind.(interface{ ListenPortChanged(uint16) }); ok {\n\t\t\tbind.ListenPortChanged(uint16(port))\n\t\t}"},
		}
	case "boundif_android.go":
		edits = [][2]string{
			{"package conn\n", "package conn\n\nimport \"syscall\"\n"},
			{"\tsysconn, err := s.ipv4.SyscallConn()", "\tif s.ipv4 == nil {\n\t\treturn -1, syscall.EINVAL\n\t}\n\tsysconn, err := s.ipv4.SyscallConn()"},
			{"\tsysconn, err := s.ipv6.SyscallConn()", "\tif s.ipv6 == nil {\n\t\treturn -1, syscall.EINVAL\n\t}\n\tsysconn, err := s.ipv6.SyscallConn()"},
		}
	case "send.go":
		edits = [][2]string{
			{"func (peer *Peer) SendHandshakeInitiation(isRetry bool) error {", "func (peer *Peer) SendHandshakeInitiation(isRetry bool) error {\n\t_, err := peer.sendHandshakeInitiation(isRetry, 0, nil)\n\treturn err\n}\n\nfunc (peer *Peer) sendHandshakeInitiation(isRetry bool, expectedEpisode uint64, cancelled func() bool) (bool, error) {\n\tpeer.device.staticIdentity.RLock()\n\tif expectedEpisode != 0 {\n\t\tif cancelled() || !peer.recovery.TryLock() {\n\t\t\tpeer.device.staticIdentity.RUnlock()\n\t\t\treturn false, nil\n\t\t}\n\t} else {\n\t\tpeer.recovery.Lock()\n\t}\n\tif expectedEpisode != 0 && (!peer.device.isUp() || !peer.isRunning.Load() || peer.recovery.exhausted != expectedEpisode || !peer.recovery.claimed) {\n\t\tpeer.recovery.Unlock()\n\t\tpeer.device.staticIdentity.RUnlock()\n\t\treturn false, nil\n\t}"},
			{"if !isRetry {\n\t\tpeer.timers.handshakeAttempts.Store(0)", "if !isRetry {\n\t\tactiveCycle := peer.recovery.activeCycle\n\t\tpeer.advanceRecoveryCycle()\n\t\tpeer.recovery.activeCycle = activeCycle\n\t\tpeer.timers.handshakeAttempts.Store(0)"},
			{"peer.handshake.mutex.RUnlock()\n\t\treturn nil", "peer.handshake.mutex.RUnlock()\n\t\tpeer.recovery.Unlock()\n\t\tpeer.device.staticIdentity.RUnlock()\n\t\treturn false, nil"},
			{"peer.handshake.mutex.Unlock()\n\t\treturn nil", "peer.handshake.mutex.Unlock()\n\t\tpeer.recovery.Unlock()\n\t\tpeer.device.staticIdentity.RUnlock()\n\t\treturn false, nil"},
			{"msg, err := peer.device.CreateMessageInitiation(peer)", "msg, err := peer.device.createMessageInitiationLocked(peer)"},
			{"peer.device.log.Errorf(\"%v - Failed to create initiation message: %v\", peer, err)\n\t\treturn err", "peer.device.log.Errorf(\"%v - Failed to create initiation message: %v\", peer, err)\n\t\tpeer.recovery.Unlock()\n\t\tpeer.device.staticIdentity.RUnlock()\n\t\treturn false, err"},
			{"packet := make([]byte, MessageInitiationSize)\n\t_ = msg.marshal(packet)\n\tpeer.cookieGenerator.AddMacs(packet)", "packet := make([]byte, MessageInitiationSize)\n\t_ = msg.marshal(packet)\n\tpeer.cookieGenerator.AddMacs(packet)\n\tif !isRetry {\n\t\tpeer.recovery.activeCycle = true\n\t}\n\tpeer.recovery.Unlock()\n\tpeer.device.staticIdentity.RUnlock()"},
			{"err = peer.SendBuffers([][]byte{packet})\n\tif err != nil {\n\t\tpeer.device.log.Errorf(\"%v - Failed to send handshake initiation: %v\", peer, err)\n\t}\n\tpeer.timersHandshakeInitiated()", "peer.observeHandshake(vpn0wnHandshakeAttempt, isRetry)\n\terr = peer.SendBuffers([][]byte{packet})\n\tif err != nil {\n\t\tpeer.device.log.Errorf(\"%v - Failed to send handshake initiation: %v\", peer, err)\n\t\tpeer.observeHandshake(vpn0wnHandshakeSendFailed, isRetry)\n\t} else {\n\t\tpeer.observeHandshake(vpn0wnHandshakeSent, isRetry)\n\t}\n\tpeer.timersHandshakeInitiated()"},
			{"peer.timersHandshakeInitiated()\n\n\treturn err", "peer.timersHandshakeInitiated()\n\n\treturn true, err"},
		}
	case "timers.go":
		edits = [][2]string{
			{"func (peer *Peer) timersActive() bool {", handshakeObserverSource + "\nfunc (peer *Peer) timersActive() bool {"},
			{"func expiredRetransmitHandshake(peer *Peer) {", "func expiredRetransmitHandshake(peer *Peer) {\n\tpeer.recovery.Lock()"},
			{"peer.timers.zeroKeyMaterial.Mod(RejectAfterTime * 3)\n\t\t}\n\t} else {", "peer.timers.zeroKeyMaterial.Mod(RejectAfterTime * 3)\n\t\t}\n\t\tepisode := uint64(0)\n\t\tif peer.recovery.activeCycle {\n\t\t\tpeer.recovery.exhausted = peer.recovery.generation\n\t\t\tepisode = peer.recovery.exhausted\n\t\t}\n\t\tpeer.recovery.Unlock()\n\t\tpeer.observeHandshakeEpisode(vpn0wnHandshakeExhausted, true, episode)\n\t} else {"},
			{"peer.timers.handshakeAttempts.Add(1)", "peer.timers.handshakeAttempts.Add(1)\n\t\tpeer.recovery.Unlock()"},
			{"func (peer *Peer) timersHandshakeComplete() {", "func (peer *Peer) timersHandshakeComplete() {\n\tpeer.recovery.Lock()\n\tpeer.advanceRecoveryCycle()"},
			{"peer.lastHandshakeNano.Store(time.Now().UnixNano())", "peer.lastHandshakeNano.Store(time.Now().UnixNano())\n\tpeer.recovery.Unlock()\n\tpeer.observeHandshake(vpn0wnHandshakeAuthenticated, false)"},
		}
	default:
		return "", fmt.Errorf("unsupported overlay file %s", name)
	}
	for i, edit := range edits {
		expected := 1
		if name == "device.go" && i == 1 {
			expected = 2
		}
		if name == "noise-protocol.go" && i == 2 {
			expected = 2
		}
		if strings.Count(source, edit[0]) != expected {
			return "", fmt.Errorf("%s overlay edit %d does not match pinned source", name, i+1)
		}
		source = strings.ReplaceAll(source, edit[0], edit[1])
	}
	return source, nil
}

const retainedRebindSource = `func (device *Device) Rebind() error {
	if !device.ipcMutex.TryLock() {
		return syscall.EBUSY
	}
	defer device.ipcMutex.Unlock()
	return device.rebindLocked()
}

func (device *Device) rebindLocked() error {
	if !device.isUp() {
		return net.ErrClosed
	}
	// Sends hold the network read lock until their captured socket I/O settles.
	if err := device.net.bind.Close(); err != nil {
		return err
	}
	if err := device.BindUpdate(); err != nil {
		return err
	}
	if !device.isUp() {
		return net.ErrClosed
	}
	return nil
}
`

const handshakeObserverSource = `const (
	vpn0wnHandshakeAttempt = iota + 1
	vpn0wnHandshakeSent
	vpn0wnHandshakeSendFailed
	vpn0wnHandshakeAuthenticated
	vpn0wnHandshakeExhausted
)

func (peer *Peer) observeHandshake(event int, retry bool) {
	peer.observeHandshakeEpisode(event, retry, 0)
}

func (peer *Peer) observeHandshakeEpisode(event int, retry bool, episode uint64) {
	if peer.device.isClosed() {
		return
	}
	observer, ok := peer.device.net.bind.(interface {
		ObserveHandshake([32]byte, int, bool, string, uint64)
	})
	if !ok {
		return
	}
	peer.endpoint.Lock()
	endpoint := ""
	if peer.endpoint.val != nil {
		endpoint = peer.endpoint.val.DstToString()
	}
	peer.endpoint.Unlock()
	observer.ObserveHandshake([32]byte(peer.handshake.remoteStatic), event, retry, endpoint, episode)
}
`
