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
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: wireguard-overlay OUTPUT_DIRECTORY")
		os.Exit(1)
	}
	if err := generate(os.Args[1]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func generate(output string) error {
	module, err := exec.Command("go", "list", "-m", "-f", "{{.Version}} {{.Dir}}", "golang.zx2c4.com/wireguard").Output()
	if err != nil {
		return fmt.Errorf("locate pinned wireguard module: %w", err)
	}
	parts := strings.SplitN(strings.TrimSpace(string(module)), " ", 2)
	if len(parts) != 2 || parts[0] != version {
		return fmt.Errorf("wireguard overlay requires %s", version)
	}
	output, err = filepath.Abs(output)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(output, 0755); err != nil {
		return err
	}
	replacements := make(map[string]string)
	for _, name := range []string{"device.go", "uapi.go"} {
		sourcePath := filepath.Join(parts[1], "device", name)
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
