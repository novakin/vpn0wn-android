/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func pinnedDirectory(t *testing.T) string {
	t.Helper()
	output, err := exec.Command("go", "list", "-m", "-f", "{{.Dir}}", "golang.zx2c4.com/wireguard").Output()
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(output))
}

func TestPinnedOverlayPreservesModuleSource(t *testing.T) {
	directory := pinnedDirectory(t)
	before := make(map[string][]byte)
	for _, file := range []string{"device/device.go", "device/peer.go", "device/noise-protocol.go", "device/uapi.go", "device/send.go", "device/timers.go", "conn/boundif_android.go"} {
		source := filepath.Join(directory, file)
		data, err := os.ReadFile(source)
		if err != nil {
			t.Fatal(err)
		}
		before[source] = data
	}
	output := t.TempDir()
	if err := generate(output, false); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(output, "overlay.json"))
	if err != nil {
		t.Fatal(err)
	}
	var overlay struct{ Replace map[string]string }
	if err := json.Unmarshal(data, &overlay); err != nil {
		t.Fatal(err)
	}
	if len(overlay.Replace) != len(before) {
		t.Fatal("unexpected overlay scope")
	}
	for source, original := range before {
		after, err := os.ReadFile(source)
		if err != nil || !bytes.Equal(original, after) {
			t.Fatal("dependency cache changed")
		}
		patched, err := os.ReadFile(overlay.Replace[source])
		if err != nil || bytes.Equal(original, patched) {
			t.Fatal("pinned source not overlaid")
		}
	}
}

func TestOverlayRejectsChangedUpstreamSource(t *testing.T) {
	directory := pinnedDirectory(t)
	for _, file := range []string{"device/device.go", "device/peer.go", "device/noise-protocol.go", "device/uapi.go", "device/send.go", "device/timers.go", "conn/boundif_android.go"} {
		source, err := os.ReadFile(filepath.Join(directory, file))
		if err != nil {
			t.Fatal(err)
		}
		if _, err := patch(filepath.Base(file), "changed upstream\n"+string(source)+string(source)); err == nil {
			t.Fatalf("%s duplicate upstream matches accepted", file)
		}
		if _, err := patch(filepath.Base(file), "unmatched upstream"); err == nil {
			t.Fatalf("%s missing upstream match accepted", file)
		}
	}
}
