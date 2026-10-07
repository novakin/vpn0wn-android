/* SPDX-License-Identifier: Apache-2.0 */

package main

import (
	"bytes"
	"testing"
)

const descriptorFixture = `package conn

func (s *StdNetBind) PeekLookAtSocketFd4() (fd int, err error) {
	sysconn, err := s.ipv4.SyscallConn()
	return
}

func (s *StdNetBind) PeekLookAtSocketFd6() (fd int, err error) {
	sysconn, err := s.ipv6.SyscallConn()
	return
}
`

func TestDescriptorOverlayGuardsBothFamiliesAndRejectsChangedInput(t *testing.T) {
	guarded, err := guardDescriptors([]byte(descriptorFixture))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Count(guarded, []byte("return -1, syscall.EINVAL")) != 2 {
		t.Fatal("missing family guard")
	}
	for _, changed := range [][]byte{
		guarded,
		[]byte(descriptorFixture + descriptorFixture),
		bytes.Replace([]byte(descriptorFixture), []byte("s.ipv6.SyscallConn()"), []byte("changed()"), 1),
	} {
		if _, err := guardDescriptors(changed); err == nil {
			t.Fatal("changed descriptor source accepted")
		}
	}
}
