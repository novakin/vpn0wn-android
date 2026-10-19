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

func patchAmneziaTransportPadding(source string) (string, error) {
	const original = `count, readErr = device.tun.device.Read(bufs, sizes, offset)
		for i := 0; i < count; i++ {
			if sizes[i] < 1 {
				continue
			}

			elem := elems[i]
			elem.packet = bufs[i][offset : offset+sizes[i]]
			elem.padding = padding`
	const replacement = `count, readErr = device.tun.device.Read(bufs, sizes, offset)
		// Configuration can change while the initial TUN read is blocked.
		padding = device.paddings.transport.Load()
		packetOffset := MessageTransportHeaderSize + int(padding)
		for i := 0; i < count; i++ {
			if sizes[i] < 1 || packetOffset+sizes[i]+MessageTransportSize > len(bufs[i]) {
				continue
			}
			if packetOffset != offset {
				copy(bufs[i][packetOffset:], bufs[i][offset:offset+sizes[i]])
			}

			elem := elems[i]
			elem.packet = bufs[i][packetOffset : packetOffset+sizes[i]]
			elem.padding = padding`
	if strings.Count(source, original) != 1 {
		return "", fmt.Errorf("AmneziaWG transport padding boundary does not match pinned source")
	}
	return strings.Replace(source, original, replacement, 1), nil
}

func addAmneziaOverlay(output, moduleRoot string, replacements map[string]string) error {
	info, err := exec.Command("go", "list", "-m", "-json", amneziaModule).Output()
	if err != nil {
		return fmt.Errorf("locate pinned AmneziaWG module: %w", err)
	}
	var module struct {
		Version string
		Dir     string
		Replace json.RawMessage
	}
	if err := json.Unmarshal(info, &module); err != nil {
		return err
	}
	if module.Version != amneziaVersion || len(module.Replace) != 0 {
		return fmt.Errorf("AmneziaWG overlay requires %s", amneziaVersion)
	}
	if module.Dir == "" {
		download, err := exec.Command("go", "mod", "download", "-json", amneziaModule+"@"+amneziaVersion).Output()
		if err != nil {
			return fmt.Errorf("download pinned AmneziaWG source: %w", err)
		}
		if err := json.Unmarshal(download, &module); err != nil {
			return err
		}
	}
	if module.Dir == "" {
		return fmt.Errorf("pinned AmneziaWG source directory unavailable")
	}
	staged := filepath.Join(output, "amneziawg")
	if err := os.RemoveAll(staged); err != nil {
		return err
	}
	if err := os.CopyFS(staged, os.DirFS(module.Dir)); err != nil {
		return err
	}
	relative, err := filepath.Rel(moduleRoot, staged)
	if err != nil {
		return err
	}
	if err := exec.Command("go", "mod", "edit", "-modfile="+filepath.Join(output, "go.mod"), "-replace="+amneziaModule+"=./"+filepath.ToSlash(relative)).Run(); err != nil {
		return err
	}
	for _, name := range []string{"device.go", "peer.go", "noise-protocol.go", "uapi.go", "send.go", "timers.go"} {
		sourcePath := filepath.Join(staged, "device", name)
		source, err := os.ReadFile(sourcePath)
		if err != nil {
			return err
		}
		patched, err := patchEngine(name, string(source), true)
		if err != nil {
			return fmt.Errorf("AmneziaWG %s: %w", name, err)
		}
		patchedPath := filepath.Join(output, "amnezia-"+name)
		if err := os.WriteFile(patchedPath, []byte(patched), 0644); err != nil {
			return err
		}
		replacements[sourcePath] = patchedPath
	}
	return nil
}
