/* SPDX-License-Identifier: Apache-2.0 */

package uapiserver

import (
	"net"
	"sync"
)

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
