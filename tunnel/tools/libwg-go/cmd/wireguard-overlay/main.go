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
	replacements := make(map[string]string)
	for _, file := range []string{"device/device.go", "device/uapi.go", "device/send.go", "device/timers.go", "conn/boundif_android.go"} {
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
		// Module-cache indexing ignores added overlay files; alias into existing host files.
		alias := strings.Replace(string(source), packageLine, header, 1) + "\n" + strings.Replace(string(descriptors), header, "", 1)
		aliasPath := filepath.Join(output, "default_host.go")
		if err := os.WriteFile(aliasPath, []byte(alias), 0644); err != nil {
			return err
		}
		replacements[hostPath] = aliasPath
		for upstream, fixture := range map[string]string{"tunnl_descriptors_test.go": "boundif_test.go", "tunnl_protection_test.go": "protection_test.go"} {
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
		replacements[filepath.Join(module.Dir, "device", "tunnl_handshake_test.go")] = fixturePath
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
			{"err = peer.SendBuffers([][]byte{packet})\n\tif err != nil {\n\t\tpeer.device.log.Errorf(\"%v - Failed to send handshake initiation: %v\", peer, err)\n\t}\n\tpeer.timersHandshakeInitiated()", "peer.observeHandshake(tunnlHandshakeAttempt, isRetry)\n\terr = peer.SendBuffers([][]byte{packet})\n\tif err != nil {\n\t\tpeer.device.log.Errorf(\"%v - Failed to send handshake initiation: %v\", peer, err)\n\t\tpeer.observeHandshake(tunnlHandshakeSendFailed, isRetry)\n\t} else {\n\t\tpeer.observeHandshake(tunnlHandshakeSent, isRetry)\n\t}\n\tpeer.timersHandshakeInitiated()"},
		}
	case "timers.go":
		edits = [][2]string{
			{"func (peer *Peer) timersActive() bool {", handshakeObserverSource + "\nfunc (peer *Peer) timersActive() bool {"},
			{"if peer.timers.handshakeAttempts.Load() > MaxTimerHandshakes {", "if peer.timers.handshakeAttempts.Load() > MaxTimerHandshakes {\n\t\tpeer.observeHandshake(tunnlHandshakeExhausted, true)"},
			{"peer.lastHandshakeNano.Store(time.Now().UnixNano())", "peer.lastHandshakeNano.Store(time.Now().UnixNano())\n\tpeer.observeHandshake(tunnlHandshakeAuthenticated, false)"},
		}
	default:
		return "", fmt.Errorf("unsupported overlay file %s", name)
	}
	for i, edit := range edits {
		expected := 1
		if name == "device.go" && i == 1 {
			expected = 2
		}
		if strings.Count(source, edit[0]) != expected {
			return "", fmt.Errorf("%s overlay edit %d does not match pinned source", name, i+1)
		}
		source = strings.ReplaceAll(source, edit[0], edit[1])
	}
	return source, nil
}

const handshakeObserverSource = `const (
	tunnlHandshakeAttempt = iota + 1
	tunnlHandshakeSent
	tunnlHandshakeSendFailed
	tunnlHandshakeAuthenticated
	tunnlHandshakeExhausted
)

func (peer *Peer) observeHandshake(event int, retry bool) {
	if peer.device.isClosed() {
		return
	}
	observer, ok := peer.device.net.bind.(interface {
		ObserveHandshake([32]byte, int, bool, string)
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
	observer.ObserveHandshake([32]byte(peer.handshake.remoteStatic), event, retry, endpoint)
}
`
