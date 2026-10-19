/* SPDX-License-Identifier: Apache-2.0 */
package androidbind

import (
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
)

func TestCapturePreparesAllFamiliesBeforeReportingAndNativeConfirmation(t *testing.T) {
	base := newFakeBind()
	b := New(func(conn.Bind, int) error { t.Fatal("legacy protection used"); return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	var prepared, reported, confirmed bool
	var released int
	b.SetInitialCapture(&Capture{
		Available: true,
		Prepare: func(opened conn.Bind, count int) error {
			return ProtectSockets(opened, count, func(fd int) bool {
				if reported || fd != 4 && fd != 6 {
					t.Fatal("capture family/order changed")
				}
				prepared = fd == 6
				return true
			})
		},
		Publish: func(generation uint64) error {
			if !prepared || b.ready != nil || generation == 0 {
				t.Fatal("report preceded preparation")
			}
			reported = true
			return nil
		},
		Confirm: func(generation uint64) error {
			if !reported || b.ready != base || generation != b.generation {
				t.Fatal("confirmation preceded native ready")
			}
			confirmed = true
			return nil
		},
		Release: func() { released++ },
	})
	if _, port, err := b.Open(51820); err != nil || port != 51820 || !confirmed {
		t.Fatal(port, err, confirmed)
	}
	if err := b.Shutdown(); err != nil || released != 1 {
		t.Fatal(err, released)
	}
}

func TestCapturePartialProtectionAndReportingFailuresCloseEntireGeneration(t *testing.T) {
	for _, stage := range []string{"second-family", "report", "confirm"} {
		t.Run(stage, func(t *testing.T) {
			base := newFakeBind()
			b := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
			b.newBind = func() conn.Bind { return base }
			failure := errors.New("capture failure")
			empty := 0
			b.SetInitialCapture(&Capture{
				Available: true,
				Prepare: func(opened conn.Bind, count int) error {
					return ProtectSockets(opened, count, func(fd int) bool { return stage != "second-family" || fd != 6 })
				},
				Publish: func(uint64) error {
					if stage == "report" {
						return failure
					}
					return nil
				},
				Confirm: func(uint64) error {
					if stage == "confirm" {
						return failure
					}
					return nil
				},
				Empty: func(uint64) error { empty++; return nil },
			})
			if fns, _, err := b.Open(51820); err == nil || fns != nil {
				t.Fatal("failed generation escaped", err)
			}
			select {
			case <-base.closed:
			default:
				t.Fatal("partial generation leaked")
			}
			if b.ready != nil || b.readyCapture != nil || empty != 1 {
				t.Fatal("failure retained published transport", empty)
			}
			b.Shutdown()
		})
	}
}

func TestShutdownWinsPublicationWhileCapturedPreparationIsBlocked(t *testing.T) {
	base := newFakeBind()
	b := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	entered, release := make(chan struct{}), make(chan struct{})
	var published atomic.Bool
	b.SetInitialCapture(&Capture{
		Available: true,
		Prepare:   func(conn.Bind, int) error { close(entered); <-release; return nil },
		Publish:   func(uint64) error { published.Store(true); return nil },
	})
	opened := make(chan error, 1)
	go func() { _, _, err := b.Open(51820); opened <- err }()
	<-entered
	closed := make(chan error, 1)
	go func() { closed <- b.Shutdown() }()
	deadline := time.After(time.Second)
	for !b.IsStopped() {
		select {
		case <-deadline:
			t.Fatal("shutdown did not invalidate before waiting")
		default:
			time.Sleep(time.Millisecond)
		}
	}
	close(release)
	if err := <-opened; !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	if published.Load() {
		t.Fatal("retired preparation published")
	}
	select {
	case <-base.closed:
	default:
		t.Fatal("retired originals leaked")
	}
}

func TestExpectedGenerationRejectsOldCaptureWithoutClosingCurrentSockets(t *testing.T) {
	base := newFakeBind()
	b := openFake(t, base, func() bool { return true })
	if installed, err := b.ReplaceCapture(b.Generation()-1, &Capture{}); installed || !errors.Is(err, ErrObsolete) {
		t.Fatal(installed, err)
	}
	select {
	case <-base.closed:
		t.Fatal("obsolete capture mutated sockets")
	default:
	}
}

func TestLossPublishesEmptyAtTheActualUnavailableGeneration(t *testing.T) {
	base := newFakeBind()
	b := openFake(t, base, func() bool { return true })
	var emptyGeneration uint64
	capture := &Capture{Empty: func(generation uint64) error { emptyGeneration = generation; return nil }}
	if installed, err := b.ReplaceCapture(b.Generation(), capture); !installed || err != nil {
		t.Fatal(installed, err)
	}
	if _, _, err := b.Open(51820); !errors.Is(err, net.ErrClosed) {
		t.Fatal(err)
	}
	if emptyGeneration != b.Generation() {
		t.Fatal("empty report lost expected native generation")
	}
	select {
	case <-base.closed:
	default:
		t.Fatal("loss retained old I/O")
	}
}

func TestSnapshotWaitsForCapturedOpenSettlement(t *testing.T) {
	base := newFakeBind()
	b := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	entered, release := make(chan struct{}), make(chan struct{})
	b.SetInitialCapture(&Capture{
		Available: true,
		Prepare:   func(conn.Bind, int) error { close(entered); <-release; return nil },
		Publish:   func(uint64) error { return nil },
	})
	opened := make(chan error, 1)
	go func() { _, _, err := b.Open(51820); opened <- err }()
	<-entered
	type snapshot struct {
		generation uint64
		ready      bool
	}
	queried := make(chan snapshot, 1)
	queryStarted := make(chan struct{})
	go func() {
		close(queryStarted)
		generation, ready := b.Snapshot()
		queried <- snapshot{generation, ready}
	}()
	<-queryStarted
	select {
	case actual := <-queried:
		close(release)
		<-opened
		b.Shutdown()
		t.Fatal("snapshot escaped unsettled preparation", actual)
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-opened; err != nil {
		t.Fatal(err)
	}
	actual := <-queried
	if !actual.ready || actual.generation != b.Generation() {
		t.Fatal(actual)
	}
	b.Shutdown()
}

type drainingCaptureBind struct {
	*fakeBind
	closing chan struct{}
	release chan struct{}
}

func (b *drainingCaptureBind) Close() error {
	close(b.closing)
	<-b.release
	return b.fakeBind.Close()
}

func TestPublishedCaptureIsReleasedOnlyAfterOriginalSocketsDrain(t *testing.T) {
	base := &drainingCaptureBind{newFakeBind(), make(chan struct{}), make(chan struct{})}
	b := New(func(conn.Bind, int) error { return nil }, func() bool { return true })
	b.newBind = func() conn.Bind { return base }
	var released atomic.Bool
	b.SetInitialCapture(&Capture{
		Available: true,
		Prepare:   func(conn.Bind, int) error { return nil },
		Publish:   func(uint64) error { return nil },
		Release: func() {
			select {
			case <-base.closed:
			default:
				t.Error("capture released before socket close settled")
			}
			released.Store(true)
		},
	})
	if _, _, err := b.Open(51820); err != nil {
		t.Fatal(err)
	}
	stopped := make(chan error, 1)
	go func() { stopped <- b.Shutdown() }()
	<-base.closing
	if released.Load() {
		t.Fatal("capture released while original close blocked")
	}
	select {
	case err := <-stopped:
		t.Fatal("shutdown settled before original close", err)
	default:
	}
	close(base.release)
	if err := <-stopped; err != nil || !released.Load() {
		t.Fatal(err, released.Load())
	}
}
