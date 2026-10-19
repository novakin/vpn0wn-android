/* SPDX-License-Identifier: Apache-2.0 */

package androidbind

import (
	"net"

	awgconn "github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"golang.zx2c4.com/wireguard/conn"
)

// Amnezia uses the same protected socket owner without the WireGuard packet detector.
type Amnezia struct {
	*Bind
}

func (b Amnezia) Open(port uint16) ([]awgconn.ReceiveFunc, uint16, error) {
	functions, actual, err := b.Bind.Open(port)
	if err != nil {
		return nil, actual, err
	}
	converted := make([]awgconn.ReceiveFunc, len(functions))
	for i, receive := range functions {
		converted[i] = func(packets [][]byte, sizes []int, endpoints []awgconn.Endpoint) (int, error) {
			original := make([]conn.Endpoint, len(endpoints))
			count, err := receive(packets, sizes, original)
			for j := 0; j < count; j++ {
				endpoints[j] = original[j]
			}
			return count, err
		}
	}
	return converted, actual, nil
}

func (b Amnezia) Send(packets [][]byte, endpoint awgconn.Endpoint) error {
	return b.send(packets, endpoint, false)
}

func (b Amnezia) SendInitiation(packets [][]byte, endpoint awgconn.Endpoint) error {
	return b.send(packets, endpoint, true)
}

func (b Amnezia) send(packets [][]byte, endpoint awgconn.Endpoint, initiation bool) error {
	b.mu.Lock()
	base := b.ready
	b.mu.Unlock()
	if base == nil || !b.active() || !b.isCurrent(base) {
		return net.ErrClosed
	}
	// The engine can prepend I/J packets beyond one native batch.
	for len(packets) != 0 {
		count := min(len(packets), base.BatchSize())
		// Only the engine's initiation owner identifies the final packet.
		if initiation && len(packets) > 1 {
			count = min(count, len(packets)-1)
		}
		if !b.active() || !b.isCurrent(base) {
			return net.ErrClosed
		}
		if err := base.Send(packets[:count], endpoint); err != nil {
			return err
		}
		packets = packets[count:]
	}
	return nil
}

func (b Amnezia) ParseEndpoint(value string) (awgconn.Endpoint, error) {
	return b.Bind.ParseEndpoint(value)
}

// AWG owns I/J packets; explicit ports must never enable WireGuard warm-up.
func (b Amnezia) ListenPortChanged(uint16) {}
