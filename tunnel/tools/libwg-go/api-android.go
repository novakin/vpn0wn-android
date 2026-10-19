/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * Modified for vpn0wn.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
// #include "jni_context.h"
import "C"

import (
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"strings"
	"unsafe"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/android/androidbind"
	"golang.zx2c4.com/wireguard/android/uapiserver"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type AndroidLogger struct {
	level C.int
	tag   *C.char
}

func cstring(s string) *C.char {
	b, err := unix.BytePtrFromString(s)
	if err != nil {
		b := [1]C.char{}
		return &b[0]
	}
	return (*C.char)(unsafe.Pointer(b))
}

func (l AndroidLogger) Printf(format string, args ...interface{}) {
	C.__android_log_write(l.level, l.tag, cstring(fmt.Sprintf(format, args...)))
}

func (h *TunnelHandle) close(env *C.JNIEnv) {
	h.closeNative()
	C.wgRethrowException(env, h.context)
	C.wgFreeContext(env, h.context)
}

func init() {
	signals := make(chan os.Signal)
	signal.Notify(signals, unix.SIGUSR2)
	go func() {
		buf := make([]byte, os.Getpagesize())
		for {
			select {
			case <-signals:
				n := runtime.Stack(buf, true)
				if n == len(buf) {
					n--
				}
				buf[n] = 0
				C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("vpn0wn/GoBackend/Stacktrace"), (*C.char)(unsafe.Pointer(&buf[0])))
			}
		}
	}()
}

//export wgTurnOn
func wgTurnOn(interfaceName string, tunFd int32, settings string, context *C.struct_wg_context, env *C.JNIEnv, capture *C.struct_wg_capture) int32 {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	tag := cstring("vpn0wn/GoBackend/" + interfaceName)
	logger := &device.Logger{
		Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
		Errorf:   AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
	}

	tun, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		if capture != nil {
			C.wgReleaseCapture(context, capture)
		}
		C.wgFreeContext(env, context)
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}

	logger.Verbosef("Attaching to interface %v", name)
	bind := androidbind.New(func(base conn.Bind, socketCount int) error {
		return androidbind.ProtectSockets(base, socketCount, func(fd int) bool {
			return C.wgProtectSocket(context, C.int(fd)) != 0
		})
	}, func() bool { return C.wgServiceActive(context) != 0 })
	if capture != nil {
		bind.SetInitialCapture(capturedTransport(context, capture, true))
	}
	bind.SetHandshakeObserver(func(peer [32]byte, event int, retry bool, endpoint string, episode uint64) {
		isRetry := C.int(0)
		if retry {
			isRetry = 1
		}
		C.wgHandshakeObservation(context, (*C.char)(unsafe.Pointer(&peer[0])), C.int(event), isRetry, cstring(endpoint), C.jlong(episode))
	})
	device := device.NewDevice(tun, bind, logger)
	handle := &TunnelHandle{device: device, bind: bind, context: context}

	err = device.IpcSet(settings)
	if err != nil {
		handle.close(env)
		logger.Errorf("IpcSet: %v", err)
		return -1
	}
	device.DisableSomeRoamingForBrokenMobileSemantics()

	uapi, err := uapiserver.Listen(name, device.IpcHandle)
	handle.uapi = uapi
	if err != nil {
		logger.Errorf("UAPI listen: %v", err)
	}

	err = device.Up()
	if err != nil {
		logger.Errorf("Unable to bring up device: %v", err)
		handle.close(env)
		return -1
	}
	if C.wgServiceActive(context) == 0 || C.wgFinishStartup(env, context) == 0 {
		handle.close(env)
		return -1
	}
	logger.Verbosef("Device started")

	id := tunnelHandles.add(handle)
	if id < 0 {
		logger.Errorf("Unable to find empty handle")
		handle.close(env)
		return -1
	}
	return id
}

func capturedTransport(context *C.struct_wg_context, capture *C.struct_wg_capture, available bool) *androidbind.Capture {
	return &androidbind.Capture{
		Available: available,
		Prepare: func(base conn.Bind, count int) error {
			return androidbind.ProtectSockets(base, count, func(fd int) bool {
				return C.wgPrepareCapturedSocket(context, capture, C.int(fd)) != 0
			})
		},
		Publish: func(generation uint64) error {
			if C.wgReportCapturedTransport(context, capture, C.jlong(generation), 1) == 0 {
				return errors.New("transport reporting failed")
			}
			return nil
		},
		Empty: func(generation uint64) error {
			if C.wgReportCapturedTransport(context, capture, C.jlong(generation), 0) == 0 {
				return errors.New("transport empty reporting failed")
			}
			return nil
		},
		Confirm: func(generation uint64) error {
			if C.wgConfirmCapturedTransport(context, capture, C.jlong(generation)) == 0 {
				return errors.New("transport publication retired")
			}
			return nil
		},
		Release: func() { C.wgReleaseCapture(context, capture) },
	}
}

//export wgTransport
func wgTransport(tunnelHandle int32, expected C.jlong, capture *C.struct_wg_capture, available C.int, env *C.JNIEnv) C.jlong {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := tunnelHandles.acquire(tunnelHandle)
	if handle == nil {
		C.wgFreeCapture(env, capture)
		return 131
	}
	defer handle.queries.Done()
	if C.wgServiceActive(handle.context) == 0 {
		C.wgFreeCapture(env, capture)
		C.wgRethrowException(env, handle.context)
		return C.jlong(handle.bind.Generation()<<8 | 131)
	}
	installed := false
	next := capturedTransport(handle.context, capture, available != 0)
	defer func() {
		if !installed {
			next.Release()
		}
	}()
	err := handle.device.RebindTransport(func() error {
		var err error
		installed, err = handle.bind.ReplaceCapture(uint64(expected), next)
		return err
	})
	outcome := 4
	if errors.Is(err, unix.EBUSY) {
		outcome = 1
	} else if errors.Is(err, androidbind.ErrObsolete) {
		outcome = 2
	} else if err != nil {
		outcome = 5
	}
	if handle.bind.IsStopped() {
		outcome = 3
	}
	if C.wgServiceActive(handle.context) == 0 {
		outcome |= 128
	}
	if C.wgRethrowException(env, handle.context) != 0 {
		outcome |= 128
	}
	return C.jlong(handle.bind.Generation()<<8 | uint64(outcome))
}

//export wgTransportSnapshot
func wgTransportSnapshot(tunnelHandle int32) C.jlong {
	handle := tunnelHandles.acquire(tunnelHandle)
	if handle == nil {
		return 0
	}
	defer handle.queries.Done()
	generation, ready := handle.bind.Snapshot()
	result := generation << 2
	if ready {
		result |= 1
	}
	if C.wgContextHealthy(handle.context) != 0 {
		result |= 2
	}
	return C.jlong(result)
}

//export wgTurnOff
func wgTurnOff(tunnelHandle int32, env *C.JNIEnv) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := tunnelHandles.remove(tunnelHandle)
	if handle == nil {
		return
	}
	handle.close(env)
}

//export wgGetConfig
func wgGetConfig(tunnelHandle int32, env *C.JNIEnv) *C.char {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := tunnelHandles.acquire(tunnelHandle)
	if handle == nil {
		return nil
	}
	defer handle.queries.Done()
	if C.wgRethrowException(env, handle.context) != 0 {
		return nil
	}
	settings, err := handle.device.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(settings)
}

//export wgRebind
func wgRebind(tunnelHandle int32, env *C.JNIEnv) int32 {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := tunnelHandles.acquire(tunnelHandle)
	if handle == nil {
		return -int32(unix.ENODEV)
	}
	defer handle.queries.Done()
	if C.wgRethrowException(env, handle.context) != 0 {
		return -int32(unix.EIO)
	}
	if C.wgServiceActive(handle.context) == 0 {
		C.wgRethrowException(env, handle.context)
		return -int32(unix.EIO)
	}
	err := handle.device.Rebind()
	if C.wgRethrowException(env, handle.context) != 0 {
		return -int32(unix.EIO)
	}
	if handle.bind.IsStopped() || errors.Is(err, net.ErrClosed) {
		return -int32(unix.ENODEV)
	}
	if errors.Is(err, unix.EBUSY) {
		return -int32(unix.EBUSY)
	}
	if err != nil {
		return -int32(unix.EIO)
	}
	return 0
}

const recoveryContextUnavailable = 1 << 8

//export wgRecover
func wgRecover(tunnelHandle int32, publicKey *C.char, episode C.jlong, endpoint string, env *C.JNIEnv, caller C.jobject) int32 {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := tunnelHandles.acquire(tunnelHandle)
	if handle == nil {
		return device.RecoveryUnavailable | recoveryContextUnavailable
	}
	defer handle.queries.Done()
	if C.wgRethrowException(env, handle.context) != 0 || C.wgServiceActive(handle.context) == 0 {
		C.wgRethrowException(env, handle.context)
		return device.RecoveryUnavailable | recoveryContextUnavailable
	}
	var key [32]byte
	copy(key[:], unsafe.Slice((*byte)(unsafe.Pointer(publicKey)), len(key)))
	callerException := false
	result := handle.device.Recover(key, uint64(episode), endpoint, func() bool {
		interrupted := C.wgCallerInterrupted(env, caller)
		callerException = interrupted < 0
		return interrupted != 0
	})
	if callerException {
		return int32(result) | recoveryContextUnavailable
	}
	if C.wgRethrowException(env, handle.context) != 0 {
		return int32(result) | recoveryContextUnavailable
	}
	if handle.bind.IsStopped() || result == device.RecoveryUnavailable || C.wgServiceActive(handle.context) == 0 {
		C.wgRethrowException(env, handle.context)
		return int32(result) | recoveryContextUnavailable
	}
	return int32(result)
}

//export wgVersion
func wgVersion() *C.char {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return C.CString("unknown")
	}
	for _, dep := range info.Deps {
		if dep.Path == "golang.zx2c4.com/wireguard" {
			parts := strings.Split(dep.Version, "-")
			if len(parts) == 3 && len(parts[2]) == 12 {
				return C.CString(parts[2][:7])
			}
			return C.CString(dep.Version)
		}
	}
	return C.CString("unknown")
}

func main() {}
