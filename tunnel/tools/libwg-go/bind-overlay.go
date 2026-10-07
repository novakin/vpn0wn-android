//go:build ignore

/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	if err := generate(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func generate() error {
	if len(os.Args) < 2 || len(os.Args) > 3 || (len(os.Args) == 3 && os.Args[2] != "host") {
		return fmt.Errorf("usage: bind-overlay output-directory [host]")
	}
	output, err := filepath.Abs(os.Args[1])
	if err != nil {
		return err
	}
	info, err := exec.Command("go", "list", "-m", "-json", "golang.zx2c4.com/wireguard").Output()
	if err != nil {
		return fmt.Errorf("locate pinned WireGuard module: %w", err)
	}
	var module struct {
		Version string
		Dir     string
		Replace json.RawMessage
	}
	if err := json.Unmarshal(info, &module); err != nil {
		return err
	}
	if module.Version != "v0.0.0-20250521234502-f333402bd9cb" || len(module.Replace) != 0 {
		return fmt.Errorf("descriptor guards require the pinned, unmodified WireGuard module")
	}
	source, err := os.ReadFile(filepath.Join(module.Dir, "conn", "boundif_android.go"))
	if err != nil {
		return err
	}
	source, err = guardDescriptors(source)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(output, 0700); err != nil {
		return err
	}
	alias := filepath.Join(output, "boundif_android.go")
	replacements := make(map[string]string)
	if len(os.Args) == 3 {
		hostPath := filepath.Join(module.Dir, "conn", "default.go")
		host, err := os.ReadFile(hostPath)
		if err != nil {
			return err
		}
		// Module-cache indexing ignores added overlay files; alias into existing host files.
		packageLine := []byte("package conn\n")
		if bytes.Count(host, packageLine) != 1 {
			return fmt.Errorf("unexpected host bind package layout")
		}
		header := []byte("package conn\n\nimport \"syscall\"\n")
		host = bytes.Replace(host, packageLine, header, 1)
		source = append(append(host, '\n'), bytes.Replace(source, header, nil, 1)...)
		alias = filepath.Join(output, "default_host.go")
		replacements[hostPath] = alias
		testPath, err := filepath.Abs(filepath.Join("tests", "boundif_test.go"))
		if err != nil {
			return err
		}
		replacements[filepath.Join(module.Dir, "conn", "conn_test.go")] = testPath
	} else {
		replacements[filepath.Join(module.Dir, "conn", "boundif_android.go")] = alias
	}
	if err := os.WriteFile(alias, source, 0600); err != nil {
		return err
	}
	overlay := struct{ Replace map[string]string }{
		Replace: replacements,
	}
	encoded, err := json.Marshal(overlay)
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(output, "overlay.json"), encoded, 0600)
}

func guardDescriptors(source []byte) ([]byte, error) {
	packageLine := []byte("package conn\n")
	if bytes.Count(source, packageLine) != 1 {
		return nil, fmt.Errorf("unexpected Android descriptor package layout")
	}
	source = bytes.Replace(source, packageLine, []byte("package conn\n\nimport \"syscall\"\n"), 1)
	for _, family := range []string{"4", "6"} {
		original := fmt.Sprintf("func (s *StdNetBind) PeekLookAtSocketFd%s() (fd int, err error) {\n\tsysconn, err := s.ipv%s.SyscallConn()", family, family)
		if bytes.Count(source, []byte(original)) != 1 {
			return nil, fmt.Errorf("unexpected IPv%s descriptor implementation", family)
		}
		guarded := fmt.Sprintf("func (s *StdNetBind) PeekLookAtSocketFd%s() (fd int, err error) {\n\tif s.ipv%s == nil {\n\t\treturn -1, syscall.EINVAL\n\t}\n\tsysconn, err := s.ipv%s.SyscallConn()", family, family, family)
		source = bytes.Replace(source, []byte(original), []byte(guarded), 1)
	}
	return source, nil
}
