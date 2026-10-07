/* SPDX-License-Identifier: Apache-2.0 */

package conn

import (
	"errors"
	"net"
	"syscall"
	"testing"
)

func TestTunnlAndroidDescriptorsMissingFamilyAndClose(t *testing.T) {
	for _, family := range []string{"udp4", "udp6"} {
		t.Run(family, func(t *testing.T) {
			socket, err := net.ListenUDP(family, nil)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { socket.Close() })
			bind := &StdNetBind{}
			available := bind.PeekLookAtSocketFd4
			missing := bind.PeekLookAtSocketFd6
			if family == "udp4" {
				bind.ipv4 = socket
			} else {
				bind.ipv6 = socket
				available, missing = missing, available
			}
			if fd, err := available(); fd < 0 || err != nil {
				t.Fatalf("available descriptor: %d/%v", fd, err)
			}
			if fd, err := missing(); fd != -1 || !errors.Is(err, syscall.EINVAL) {
				t.Fatalf("missing descriptor: %d/%v", fd, err)
			}
			if err := bind.Close(); err != nil {
				t.Fatal(err)
			}
			for _, peek := range []func() (int, error){available, missing} {
				if fd, err := peek(); fd != -1 || !errors.Is(err, syscall.EINVAL) {
					t.Fatalf("closed descriptor: %d/%v", fd, err)
				}
			}
		})
	}
}

func TestTunnlAndroidDescriptorsUnopened(t *testing.T) {
	bind := &StdNetBind{}
	for _, peek := range []func() (int, error){bind.PeekLookAtSocketFd4, bind.PeekLookAtSocketFd6} {
		if fd, err := peek(); fd != -1 || !errors.Is(err, syscall.EINVAL) {
			t.Fatalf("unopened descriptor: %d/%v", fd, err)
		}
	}
}
