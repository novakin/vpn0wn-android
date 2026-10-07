/* SPDX-License-Identifier: Apache-2.0 */

package uapiserver

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

var socketDirectory = "/var/run/wireguard"

const socketProbeTimeout = time.Second

func Listen(name string, handle func(net.Conn)) (*Server, error) {
	if err := os.MkdirAll(socketDirectory, 0700); err != nil {
		return nil, err
	}
	listener, err := listenSocket(filepath.Join(socketDirectory, name+".sock"))
	if err != nil {
		return nil, err
	}
	return New(listener, handle), nil
}

func listenSocket(path string) (*net.UnixListener, error) {
	address := &net.UnixAddr{Name: path, Net: "unix"}
	listener, err := net.ListenUnix("unix", address)
	if errors.Is(err, syscall.EADDRINUSE) {
		probe, probeErr := net.DialTimeout("unix", path, socketProbeTimeout)
		if probeErr == nil {
			probe.Close()
			return nil, err
		}
		if !errors.Is(probeErr, syscall.ECONNREFUSED) {
			return nil, probeErr
		}
		info, statErr := os.Lstat(path)
		if statErr != nil {
			return nil, statErr
		}
		if info.Mode()&os.ModeSocket == 0 {
			return nil, errors.New("UAPI path is not a socket")
		}
		if removeErr := os.Remove(path); removeErr != nil {
			return nil, removeErr
		}
		listener, err = net.ListenUnix("unix", address)
	}
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0600); err != nil {
		listener.Close()
		return nil, err
	}
	return listener, nil
}

type Server struct {
	listener net.Listener
	mu       sync.Mutex
	clients  map[net.Conn]struct{}
	accept   sync.WaitGroup
	handlers sync.WaitGroup
	close    sync.Once
	closeErr error
}

func New(listener net.Listener, handle func(net.Conn)) *Server {
	s := &Server{listener: listener, clients: make(map[net.Conn]struct{})}
	s.accept.Add(1)
	go s.serve(handle)
	return s
}

func (s *Server) serve(handle func(net.Conn)) {
	defer s.accept.Done()
	for {
		client, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.clients[client] = struct{}{}
		s.handlers.Add(1)
		s.mu.Unlock()
		go func() {
			defer s.handlers.Done()
			defer func() {
				client.Close()
				s.mu.Lock()
				delete(s.clients, client)
				s.mu.Unlock()
			}()
			handle(client)
		}()
	}
}

func (s *Server) Close() error {
	s.close.Do(func() {
		s.closeErr = s.listener.Close()
		// Stop admission before waiting: Accept can return a final client while Close runs.
		s.accept.Wait()
		s.mu.Lock()
		for client := range s.clients {
			client.Close()
		}
		s.mu.Unlock()
		s.handlers.Wait()
	})
	return s.closeErr
}
