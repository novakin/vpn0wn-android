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
		relative, err := filepath.Rel(directory, source)
		if err != nil {
			t.Fatal(err)
		}
		staged := filepath.Join(output, "wireguard", relative)
		copied, err := os.ReadFile(staged)
		if err != nil || !bytes.Equal(original, copied) {
			t.Fatal("staged module source changed")
		}
		patched, err := os.ReadFile(overlay.Replace[staged])
		if err != nil || bytes.Equal(original, patched) {
			t.Fatal("pinned source not overlaid")
		}
	}
	metadata, err := exec.Command("go", "list", "-modfile="+filepath.Join(output, "go.mod"), "-m", "-json", "golang.zx2c4.com/wireguard").Output()
	if err != nil {
		t.Fatal(err)
	}
	var module struct {
		Version string
		Replace struct{ Dir, Path string }
	}
	if err := json.Unmarshal(metadata, &module); err != nil {
		t.Fatal(err)
	}
	if module.Version != version || module.Replace.Dir != filepath.Join(output, "wireguard") || filepath.IsAbs(module.Replace.Path) {
		t.Fatal("generated module does not use the pinned staged source")
	}
}

func TestOverlayRejectsModuleReplacement(t *testing.T) {
	output := t.TempDir()
	if err := generate(output, false); err != nil {
		t.Fatal(err)
	}
	t.Chdir(output)
	if err := generate(t.TempDir(), false); err == nil || !strings.Contains(err.Error(), "wireguard overlay requires "+version) {
		t.Fatalf("module replacement was not rejected: %v", err)
	}
}

func TestOverlayRejectsUnexpectedModuleVersion(t *testing.T) {
	directory := t.TempDir()
	command := "#!/bin/sh\nprintf '%s' '{\"Version\":\"v0.0.0-test\"}'\n"
	if err := os.WriteFile(filepath.Join(directory, "go"), []byte(command), 0755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", directory+string(os.PathListSeparator)+os.Getenv("PATH"))
	if err := generate(t.TempDir(), false); err == nil || !strings.Contains(err.Error(), "wireguard overlay requires "+version) {
		t.Fatalf("unexpected module version was not rejected: %v", err)
	}
}

func TestOverlayRefreshesStagedModule(t *testing.T) {
	output := t.TempDir()
	if err := generate(output, false); err != nil {
		t.Fatal(err)
	}
	stale := filepath.Join(output, "wireguard", "device", "stale.go")
	if err := os.WriteFile(stale, []byte("stale generated source"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := generate(output, false); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Fatalf("stale generated source survived: %v", err)
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
