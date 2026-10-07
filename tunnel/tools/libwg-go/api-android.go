/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"strings"
	"unsafe"

	"golang.org/x/sys/unix"
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
func wgTurnOn(interfaceName string, tunFd int32, settings string) int32 {
	tag := cstring("WireGuard/GoBackend/" + interfaceName)
	logger := &device.Logger{
		Verbosef: AndroidLogger{level: C.ANDROID_LOG_DEBUG, tag: tag}.Printf,
		Errorf:   AndroidLogger{level: C.ANDROID_LOG_ERROR, tag: tag}.Printf,
	}

	tun, name, err := tun.CreateUnmonitoredTUNFromFD(int(tunFd))
	if err != nil {
		unix.Close(int(tunFd))
		logger.Errorf("CreateUnmonitoredTUNFromFD: %v", err)
		return -1
	}

	logger.Verbosef("Attaching to interface %v", name)
	bind := &tunnelBind{base: conn.NewStdNetBind()}
	device := device.NewDevice(tun, bind, logger)
	handle := &TunnelHandle{device: device, bind: bind}

	err = device.IpcSet(settings)
	if err != nil {
		handle.closeNative()
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
		handle.closeNative()
		return -1
	}
	logger.Verbosef("Device started")

	id := tunnelHandles.add(handle)
	if id < 0 {
		logger.Errorf("Unable to find empty handle")
		handle.closeNative()
		return -1
	}
	return id
}

//export wgTurnOff
func wgTurnOff(tunnelHandle int32) {
	handle := tunnelHandles.remove(tunnelHandle)
	if handle == nil {
		return
	}
	handle.closeNative()
}

//export wgGetSocketV4
func wgGetSocketV4(tunnelHandle int32) int32 {
	return tunnelHandles.socketV4(tunnelHandle)
}

//export wgGetSocketV6
func wgGetSocketV6(tunnelHandle int32) int32 {
	return tunnelHandles.socketV6(tunnelHandle)
}

//export wgGetConfig
func wgGetConfig(tunnelHandle int32) *C.char {
	settings, ok := tunnelHandles.config(tunnelHandle)
	if !ok {
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
