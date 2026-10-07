/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
// #include "jni_context.h"
import "C"

import (
	"fmt"
	"math"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
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

type TunnelHandle struct {
	mu      sync.Mutex
	device  *device.Device
	bind    *androidbind.Bind
	uapi    *uapiserver.Server
	context *C.struct_wg_context
}

var tunnelHandles = struct {
	sync.Mutex
	items map[int32]*TunnelHandle
}{items: make(map[int32]*TunnelHandle)}

func (h *TunnelHandle) close(env *C.JNIEnv) {
	h.bind.Shutdown()
	if h.uapi != nil {
		h.uapi.Close()
	}
	h.device.Close()
	C.wgRethrowException(env, h.context)
	C.wgFreeContext(env, h.context)
}

func lockHandle(id int32) *TunnelHandle {
	tunnelHandles.Lock()
	h := tunnelHandles.items[id]
	if h != nil {
		h.mu.Lock()
	}
	tunnelHandles.Unlock()
	return h
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
				C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("WireGuard/GoBackend/Stacktrace"), (*C.char)(unsafe.Pointer(&buf[0])))
			}
		}
	}()
}

//export wgTurnOn
func wgTurnOn(interfaceName string, tunFd int32, settings string, context *C.struct_wg_context, env *C.JNIEnv) int32 {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	tag := cstring("WireGuard/GoBackend/" + interfaceName)
	logger := &device.Logger{
		Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
		Errorf:   AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
	}

	tun, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
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

	var i int32
	tunnelHandles.Lock()
	defer tunnelHandles.Unlock()
	for i = 0; i < math.MaxInt32; i++ {
		if _, exists := tunnelHandles.items[i]; !exists {
			break
		}
	}
	if i == math.MaxInt32 {
		logger.Errorf("Unable to find empty handle")
		handle.close(env)
		return -1
	}
	tunnelHandles.items[i] = handle
	return i
}

//export wgTurnOff
func wgTurnOff(tunnelHandle int32, env *C.JNIEnv) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	tunnelHandles.Lock()
	handle := tunnelHandles.items[tunnelHandle]
	delete(tunnelHandles.items, tunnelHandle)
	tunnelHandles.Unlock()
	if handle == nil {
		return
	}
	handle.mu.Lock()
	defer handle.mu.Unlock()
	handle.close(env)
}

//export wgGetConfig
func wgGetConfig(tunnelHandle int32, env *C.JNIEnv) *C.char {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	handle := lockHandle(tunnelHandle)
	if handle == nil {
		return nil
	}
	defer handle.mu.Unlock()
	if C.wgRethrowException(env, handle.context) != 0 {
		return nil
	}
	settings, err := handle.device.IpcGet()
	if err != nil {
		return nil
	}
	return C.CString(settings)
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
