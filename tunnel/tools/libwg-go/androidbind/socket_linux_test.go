/* SPDX-License-Identifier: Apache-2.0 */

package androidbind

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
)

func exclusiveUDP(family, host string, port uint16) (net.PacketConn, error) {
	config := net.ListenConfig{Control: func(_, _ string, raw syscall.RawConn) error {
		var optionError error
		err := raw.Control(func(fd uintptr) {
			for _, option := range []int{unix.SO_REUSEADDR, unix.SO_REUSEPORT} {
				if optionError = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, option, 0); optionError != nil {
					return
				}
			}
			if family == "udp6" {
				optionError = unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_V6ONLY, 1)
			}
		})
		if err != nil {
			return err
		}
		return optionError
	}}
	return config.ListenPacket(context.Background(), family, net.JoinHostPort(host, strconv.Itoa(int(port))))
}

func assertPortOwnership(t *testing.T, port uint16, occupied bool) {
	t.Helper()
	if port == 0 {
		t.Fatal("no actual UDP port selected")
	}
	for _, family := range []string{"udp4", "udp6"} {
		contender, err := exclusiveUDP(family, "", port)
		if contender != nil {
			if closeError := contender.Close(); closeError != nil {
				t.Fatal(closeError)
			}
		}
		if occupied {
			if !errors.Is(err, syscall.EADDRINUSE) {
				t.Fatalf("%s acquired occupied port %d or failed for another reason: %v", family, port, err)
			}
		} else if err != nil {
			t.Fatalf("%s cannot reacquire released port %d: %v", family, port, err)
		}
	}
}

func assertSocketGeneration(t *testing.T, base conn.Bind, port uint16, open bool) {
	t.Helper()
	sockets, ok := base.(conn.PeekLookAtSocketFd)
	if !ok {
		t.Fatal("real bind has no descriptor access")
	}
	for family, peek := range map[string]func() (int, error){
		"udp4": sockets.PeekLookAtSocketFd4,
		"udp6": sockets.PeekLookAtSocketFd6,
	} {
		fd, err := peek()
		if !open {
			if fd != -1 || !errors.Is(err, syscall.EINVAL) {
				t.Fatalf("retired %s generation retained descriptor: %d/%v", family, fd, err)
			}
			continue
		}
		if err != nil || fd < 0 {
			t.Fatalf("required %s descriptor unavailable: %d/%v", family, fd, err)
		}
		address, err := syscall.Getsockname(fd)
		if err != nil {
			t.Fatal(err)
		}
		actual := 0
		switch address := address.(type) {
		case *syscall.SockaddrInet4:
			if family != "udp4" {
				t.Fatal("IPv6 descriptor refers to IPv4 socket")
			}
			actual = address.Port
		case *syscall.SockaddrInet6:
			if family != "udp6" {
				t.Fatal("IPv4 descriptor refers to IPv6 socket")
			}
			actual = address.Port
		default:
			t.Fatalf("unexpected %s socket address %T", family, address)
		}
		if actual != int(port) {
			t.Fatalf("%s socket uses %d, expected %d", family, actual, port)
		}
	}
}

func TestRealBindCloseAndShutdownReleasePorts(t *testing.T) {
	var generations []conn.Bind
	b := New(func(base conn.Bind, count int) error {
		generations = append(generations, base)
		return ProtectSockets(base, count, func(int) bool { return true })
	}, func() bool { return true })
	t.Cleanup(func() { b.Shutdown() })
	_, port, err := b.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	assertSocketGeneration(t, generations[0], port, true)
	assertPortOwnership(t, port, true)
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	assertSocketGeneration(t, generations[0], port, false)
	assertPortOwnership(t, port, false)
	_, reopened, err := b.Open(port)
	if err != nil || reopened != port {
		t.Fatalf("reopen changed port: %d/%v", reopened, err)
	}
	assertSocketGeneration(t, generations[1], port, true)
	assertPortOwnership(t, port, true)
	if err := b.Shutdown(); err != nil {
		t.Fatal(err)
	}
	assertSocketGeneration(t, generations[1], port, false)
	assertPortOwnership(t, port, false)
	if _, _, err := b.Open(port); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("terminal bind reopened: %v", err)
	}
}

func assertInitiationSource(t *testing.T, b *Bind, port uint16, warmup bool) {
	t.Helper()
	for family, host := range map[string]string{"udp4": "127.0.0.1", "udp6": "::1"} {
		receiver, err := exclusiveUDP(family, host, 0)
		if err != nil {
			t.Fatalf("required %s loopback receiver: %v", family, err)
		}
		t.Cleanup(func() { receiver.Close() })
		endpoint, err := b.ParseEndpoint(receiver.LocalAddr().String())
		if err != nil {
			t.Fatal(err)
		}
		if err := b.Send([][]byte{initiation()}, endpoint); err != nil {
			t.Fatal(err)
		}
		expected := [][]byte{initiation()}
		if warmup {
			expected = append([][]byte{{0}}, expected...)
		}
		if err := receiver.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
			t.Fatal(err)
		}
		for _, packet := range expected {
			buffer := make([]byte, 2048)
			length, source, err := receiver.ReadFrom(buffer)
			if err != nil {
				t.Fatalf("%s packet reception: %v", family, err)
			}
			if !bytes.Equal(buffer[:length], packet) || source.(*net.UDPAddr).Port != int(port) {
				t.Fatalf("%s packet content or source port changed", family)
			}
		}
		if err := receiver.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRealDeviceRebindAndTeardownRetainPortIntent(t *testing.T) {
	for _, intent := range []string{"omitted", "zero", "explicit"} {
		t.Run(intent, func(t *testing.T) {
			var denied bool
			var generations []conn.Bind
			b := New(func(base conn.Bind, count int) error {
				generations = append(generations, base)
				protected := 0
				return ProtectSockets(base, count, func(int) bool {
					protected++
					return !denied || protected == 1
				})
			}, func() bool { return true })
			d, _, tunnel := quietDevice(t, b)
			var requestedPort uint16
			if intent != "omitted" {
				if intent == "explicit" {
					reservation := conn.NewStdNetBind()
					_, selected, err := reservation.Open(0)
					if err != nil {
						t.Fatal(err)
					}
					if err := reservation.Close(); err != nil {
						t.Fatal(err)
					}
					requestedPort = selected
				}
				if err := d.IpcSet(fmt.Sprintf("listen_port=%d\n", requestedPort)); err != nil {
					t.Fatal(err)
				}
			}
			if err := d.Up(); err != nil {
				t.Fatal(err)
			}
			port := configPort(t, d)
			if intent == "explicit" && port != requestedPort {
				t.Fatalf("startup replaced explicit port %d with %d", requestedPort, port)
			}
			assertSocketGeneration(t, generations[0], port, true)
			assertPortOwnership(t, port, true)
			assertInitiationSource(t, b, port, intent == "explicit")
			if err := d.Rebind(); err != nil {
				t.Fatal(err)
			}
			assertSocketGeneration(t, generations[0], port, false)
			assertSocketGeneration(t, generations[1], port, true)
			assertPortOwnership(t, port, true)
			assertInitiationSource(t, b, port, intent == "explicit")
			denied = true
			if err := d.Rebind(); err == nil {
				t.Fatal("partial protection denial accepted")
			}
			assertSocketGeneration(t, generations[1], port, false)
			assertSocketGeneration(t, generations[2], port, false)
			assertPortOwnership(t, port, false)
			if configPort(t, d) != port || b.warmup.Load() != (intent == "explicit") || tunnel.closeCalls.Load() != 0 {
				t.Fatal("failed rebind lost port, explicit intent or retained TUN")
			}
			denied = false
			if err := d.Rebind(); err != nil {
				t.Fatal(err)
			}
			assertSocketGeneration(t, generations[3], port, true)
			assertPortOwnership(t, port, true)
			assertInitiationSource(t, b, port, intent == "explicit")
			if configPort(t, d) != port || tunnel.closeCalls.Load() != 0 {
				t.Fatal("successful rebind lost actual port or retained TUN")
			}
			if err := d.Down(); err != nil {
				t.Fatal(err)
			}
			assertSocketGeneration(t, generations[3], port, false)
			assertPortOwnership(t, port, false)
			if err := d.Up(); err != nil {
				t.Fatal(err)
			}
			assertSocketGeneration(t, generations[4], port, true)
			assertPortOwnership(t, port, true)
			d.Close()
			assertSocketGeneration(t, generations[4], port, false)
			assertPortOwnership(t, port, false)
			if tunnel.closeCalls.Load() != 1 {
				t.Fatal("device closure did not release retained TUN")
			}
		})
	}
}
