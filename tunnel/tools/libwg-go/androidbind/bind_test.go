/* SPDX-License-Identifier: Apache-2.0 */

package androidbind

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"net"
	"reflect"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
)

type fakeBind struct {
	mu               sync.Mutex
	packets          [][]byte
	endpoints        []conn.Endpoint
	closed           chan struct{}
	closeOnce        sync.Once
	openPort         uint16
	onSend           func([][]byte) error
	markError        error
	v4, v6           int
	v4Error, v6Error error
}

func newFakeBind() *fakeBind { return &fakeBind{closed: make(chan struct{}), v4: 4, v6: 6} }
func (f *fakeBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	f.openPort = port
	if port == 0 {
		port = 45678
	}
	receive := func([][]byte, []int, []conn.Endpoint) (int, error) {
		<-f.closed
		return 0, net.ErrClosed
	}
	return []conn.ReceiveFunc{receive, receive}, port, nil
}
func (f *fakeBind) Close() error {
	f.closeOnce.Do(func() { close(f.closed) })
	return nil
}
func (f *fakeBind) SetMark(uint32) error { return f.markError }
func (f *fakeBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	return conn.NewStdNetBind().ParseEndpoint(s)
}
func (f *fakeBind) BatchSize() int                    { return 1 }
func (f *fakeBind) PeekLookAtSocketFd4() (int, error) { return f.v4, f.v4Error }
func (f *fakeBind) PeekLookAtSocketFd6() (int, error) { return f.v6, f.v6Error }
func (f *fakeBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	select {
	case <-f.closed:
		return net.ErrClosed
	default:
	}
	f.mu.Lock()
	for _, p := range packets {
		f.packets = append(f.packets, bytes.Clone(p))
		f.endpoints = append(f.endpoints, endpoint)
	}
	f.mu.Unlock()
	if f.onSend != nil {
		return f.onSend(packets)
	}
	return nil
}
func (f *fakeBind) sent() [][]byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]byte(nil), f.packets...)
}

func initiation() []byte {
	p := make([]byte, device.MessageInitiationSize)
	binary.LittleEndian.PutUint32(p, device.MessageInitiationType)
	return p
}
func openFake(t *testing.T, base *fakeBind, active func() bool) *Bind {
	t.Helper()
	b := New(func(conn.Bind, int) error { return nil }, active)
	b.newBind = func() conn.Bind { return base }
	if _, _, err := b.Open(51820); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Shutdown() })
	return b
}

func TestProtectSocketsFamiliesAndDenial(t *testing.T) {
	for _, test := range []struct {
		name             string
		v4Error, v6Error error
		count            int
		deny             int
		wantError        bool
		calls            []int
	}{
		{"both", nil, nil, 2, -1, false, []int{4, 6}},
		{"v4 only", nil, syscall.EINVAL, 1, -1, false, []int{4}},
		{"v6 only", syscall.EINVAL, nil, 1, -1, false, []int{6}},
		{"none", syscall.EINVAL, syscall.EINVAL, 0, -1, true, nil},
		{"count mismatch", nil, syscall.EINVAL, 2, -1, true, []int{4}},
		{"descriptor failure", syscall.EBADF, nil, 2, -1, true, nil},
		{"denied second", nil, nil, 2, 6, true, []int{4, 6}},
	} {
		t.Run(test.name, func(t *testing.T) {
			base := newFakeBind()
			base.v4Error, base.v6Error = test.v4Error, test.v6Error
			var calls []int
			err := ProtectSockets(base, test.count, func(fd int) bool {
				calls = append(calls, fd)
				return fd != test.deny
			})
			if (err != nil) != test.wantError || !reflect.DeepEqual(calls, test.calls) {
				t.Fatalf("err=%v calls=%v", err, calls)
			}
		})
	}
}

func TestOpenDoesNotPublishBeforeProtection(t *testing.T) {
	base := newFakeBind()
	entered, release := make(chan struct{}), make(chan struct{})
	b := New(func(conn.Bind, int) error {
		close(entered)
		<-release
		return nil
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	done := make(chan error, 1)
	go func() {
		_, _, err := b.Open(51820)
		done <- err
	}()
	<-entered
	cookie := make([]byte, device.MessageCookieReplySize)
	if err := b.Send([][]byte{cookie}, nil); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("unprotected cookie: %v", err)
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	defer b.Shutdown()
	if err := b.Send([][]byte{cookie}, nil); err != nil {
		t.Fatal(err)
	}
	if len(base.sent()) != 1 {
		t.Fatal("cookie reached unprotected generation")
	}
}

func TestProtectionFailureClosesUnpublishedSockets(t *testing.T) {
	base := newFakeBind()
	b := New(func(base conn.Bind, count int) error {
		return ProtectSockets(base, count, func(int) bool { return false })
	}, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	if _, _, err := b.Open(51820); err == nil {
		t.Fatal("protection denial accepted")
	}
	select {
	case <-base.closed:
	default:
		t.Fatal("unprotected sockets retained")
	}
	if err := b.Send([][]byte{initiation()}, nil); !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	if len(base.sent()) != 0 {
		t.Fatal("sent after denial")
	}
}

func TestPerInitiationCardinalityAndEndpoint(t *testing.T) {
	base := newFakeBind()
	b := openFake(t, base, func() bool { return true })
	b.ListenPortChanged(51820)
	ep, err := b.ParseEndpoint("127.0.0.1:51821")
	if err != nil {
		t.Fatal(err)
	}
	response := make([]byte, device.MessageResponseSize)
	binary.LittleEndian.PutUint32(response, device.MessageResponseType)
	cookie := make([]byte, device.MessageCookieReplySize)
	binary.LittleEndian.PutUint32(cookie, device.MessageCookieReplyType)
	data := make([]byte, device.MessageTransportSize)
	binary.LittleEndian.PutUint32(data, device.MessageTransportType)
	init := initiation()
	if err := b.Send([][]byte{response, init, cookie, data, init}, ep); err != nil {
		t.Fatal(err)
	}
	want := [][]byte{response, {0}, init, cookie, data, {0}, init}
	if !reflect.DeepEqual(base.sent(), want) {
		t.Fatal("warm-up changed packet order/cardinality")
	}
	for _, actual := range base.endpoints {
		if actual != ep {
			t.Fatal("endpoint changed")
		}
	}
	b.ListenPortChanged(0)
	if err := b.Send([][]byte{init}, ep); err != nil {
		t.Fatal(err)
	}
	if len(base.sent()) != len(want)+1 {
		t.Fatal("zero intent did not disable warm-up")
	}
}

func TestAutomaticallyAssignedPortDoesNotEnableWarmup(t *testing.T) {
	base := newFakeBind()
	b := openFake(t, base, func() bool { return true })
	if err := b.Send([][]byte{initiation()}, nil); err != nil {
		t.Fatal(err)
	}
	if len(base.sent()) != 1 {
		t.Fatal("Open inferred explicit port intent")
	}
}

func TestWarmupFailureSemantics(t *testing.T) {
	for _, test := range []struct {
		name              string
		err               error
		continueHandshake bool
	}{
		{"expected IO", &net.OpError{Op: "write", Net: "udp", Err: syscall.ENETUNREACH}, true},
		{"closed", net.ErrClosed, false}, {"cancelled", context.Canceled, false},
		{"deadline", context.DeadlineExceeded, false}, {"bad descriptor", syscall.EBADF, false},
		{"unexpected", errors.New("unexpected defect"), false},
		{"wrapped cancellation", &net.OpError{Op: "write", Err: context.Canceled}, false},
		{"offload fallback succeeded", conn.ErrUDPGSODisabled{}, true},
		{"offload closed", conn.ErrUDPGSODisabled{RetryErr: net.ErrClosed}, false},
	} {
		t.Run(test.name, func(t *testing.T) {
			base := newFakeBind()
			base.onSend = func(p [][]byte) error {
				if len(p[0]) == 1 {
					return test.err
				}
				return nil
			}
			b := openFake(t, base, func() bool { return true })
			b.ListenPortChanged(51820)
			err := b.Send([][]byte{initiation()}, nil)
			if (err == nil) != test.continueHandshake {
				t.Fatalf("err=%v", err)
			}
			want := 1
			if test.continueHandshake {
				want = 2
			}
			if len(base.sent()) != want {
				t.Fatalf("sent %d, want %d", len(base.sent()), want)
			}
		})
	}
}

func TestServiceDestructionBetweenWarmupAndInitiation(t *testing.T) {
	var active atomic.Bool
	active.Store(true)
	base := newFakeBind()
	base.onSend = func([][]byte) error {
		active.Store(false)
		return syscall.ENETUNREACH
	}
	b := openFake(t, base, active.Load)
	b.ListenPortChanged(51820)
	if err := b.Send([][]byte{initiation()}, nil); !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	if len(base.sent()) != 1 {
		t.Fatal("initiation sent after destruction")
	}
}

func TestCloseDuringWarmupCannotUseReplacementGeneration(t *testing.T) {
	old, replacement := newFakeBind(), newFakeBind()
	entered := make(chan struct{})
	old.onSend = func([][]byte) error {
		close(entered)
		<-old.closed
		return nil
	}
	b := openFake(t, old, func() bool { return true })
	b.ListenPortChanged(51820)
	done := make(chan error, 1)
	go func() { done <- b.Send([][]byte{initiation()}, nil) }()
	<-entered
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	b.newBind = func() conn.Bind { return replacement }
	if _, _, err := b.Open(51820); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("send did not settle")
	}
	if len(old.sent()) != 1 || len(replacement.sent()) != 0 {
		t.Fatal("old initiation crossed generation")
	}
}

func TestShutdownUnblocksWarmupAndRejectsReopen(t *testing.T) {
	base := newFakeBind()
	entered := make(chan struct{})
	base.onSend = func([][]byte) error {
		close(entered)
		<-base.closed
		return net.ErrClosed
	}
	b := openFake(t, base, func() bool { return true })
	b.ListenPortChanged(51820)
	done := make(chan error, 1)
	go func() { done <- b.Send([][]byte{initiation()}, nil) }()
	<-entered
	if err := b.Shutdown(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("shutdown retained send")
	}
	if _, _, err := b.Open(51820); !errors.Is(err, net.ErrClosed) {
		t.Fatal("terminal shutdown reopened")
	}
}
