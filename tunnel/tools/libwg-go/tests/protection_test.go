/* SPDX-License-Identifier: Apache-2.0 */

package conn_test

import (
	"testing"

	"golang.zx2c4.com/wireguard/android/androidbind"
	"golang.zx2c4.com/wireguard/conn"
)

func TestTunnlActualFamilySocketProtection(t *testing.T) {
	for _, family := range []string{"udp4", "udp6"} {
		t.Run(family, func(t *testing.T) {
			bind, err := conn.NewTunnlTestFamilyBind(family)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { bind.Close() })
			peek := bind.PeekLookAtSocketFd4
			if family == "udp6" {
				peek = bind.PeekLookAtSocketFd6
			}
			available, err := peek()
			if available < 0 || err != nil {
				t.Fatalf("available family descriptor: %d/%v", available, err)
			}
			var protected []int
			err = androidbind.ProtectSockets(bind, 1, func(fd int) bool {
				protected = append(protected, fd)
				return true
			})
			if err != nil || len(protected) != 1 || protected[0] != available {
				t.Fatalf("family protection: descriptors=%v error=%v", protected, err)
			}
			denied := 0
			err = androidbind.ProtectSockets(bind, 1, func(int) bool { denied++; return false })
			if err == nil || denied != 1 {
				t.Fatalf("denied protection: callbacks=%d error=%v", denied, err)
			}
			if err := bind.Close(); err != nil {
				t.Fatal(err)
			}
			assertUnavailableProtection(t, bind)
		})
	}
}

func TestTunnlActualUnopenedSocketProtection(t *testing.T) {
	assertUnavailableProtection(t, &conn.StdNetBind{})
}

func assertUnavailableProtection(t *testing.T, bind conn.Bind) {
	t.Helper()
	called := false
	err := androidbind.ProtectSockets(bind, 1, func(int) bool { called = true; return true })
	if err == nil || called {
		t.Fatalf("unavailable protection: callback=%v error=%v", called, err)
	}
}
