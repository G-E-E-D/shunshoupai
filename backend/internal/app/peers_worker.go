package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sync"
	"time"
)

const maxPeerCursor = "ffffffff-ffff-ffff-ffff-ffffffffffff"

func (s *Store) SyncPeer(peer PeerConnection) error {
	local, e := loadIdentity(s.DB)
	if e != nil {
		return e
	}
	generation, e := peerGeneration(s.DB)
	if e != nil {
		return e
	}
	if _, e = verifyPeer(peer.Origin, local.ID, &peer); e != nil {
		return e
	}
	pushCursor, pullCursor := "", ""
	pushDone, pullDone := false, false
	started := time.Now()
	for pages := 0; pages < 10000 && time.Since(started) < 120*time.Second; pages++ {
		if !s.peerActive(peer.ID) {
			return nil
		}
		push := Object{"records": []ReplicaRecord{}, "has_more": false, "next_cursor": nil}
		if !pushDone {
			push, e = s.ReplicaPage(local.SpaceID, pushCursor)
			if e != nil {
				return e
			}
		}
		body := Object{"device_id": local.ID, "records": push["records"]}
		if pullCursor != "" {
			body["after"] = pullCursor
		}
		var reply struct {
			PeerID     string          `json:"peer_id"`
			SpaceID    string          `json:"space_id"`
			Records    []ReplicaRecord `json:"records"`
			HasMore    *bool           `json:"has_more"`
			NextCursor *string         `json:"next_cursor"`
		}
		if e = peerRequest(peer.Origin, "/api/replica/exchange", "POST", body, peer.Token, &reply); e != nil {
			return e
		}
		if reply.PeerID != peer.ID || reply.SpaceID != peer.SpaceID {
			return appErr("PEER_IDENTITY_CHANGED", "对方日程身份改变，已保留本机内容。", 409)
		}
		if reply.HasMore == nil || reply.Records == nil || len(reply.Records) > 50 || (reply.NextCursor != nil && !deviceUUIDPattern.MatchString(*reply.NextCursor)) {
			return appErr("PEER_UNAVAILABLE", "对方返回了无效同步页。", 502)
		}
		if !s.peerActive(peer.ID) {
			return nil
		}
		_, e = s.Exchange(local.SpaceID, reply.Records, maxPeerCursor, func(db DBTX) error {
			current, e := peerGeneration(db)
			if e != nil {
				return e
			}
			var n int
			e = db.QueryRow("SELECT count(*) FROM peer_connections WHERE id=? AND origin=? AND public_key=? AND enabled=1", peer.ID, peer.Origin, peer.PublicKey).Scan(&n)
			if e != nil {
				return e
			}
			if n != 1 || current != generation {
				return appErr("PAIRING_CANCELLED", "配对已解除，未恢复连接。", 409)
			}
			return nil
		})
		if e != nil {
			return e
		}
		more, _ := push["has_more"].(bool)
		pushDone = !more
		if more {
			cursor, ok := push["next_cursor"].(string)
			if !ok || cursor <= pushCursor {
				return appErr("INVALID_CURSOR", "本机同步游标无效。", 409)
			}
			pushCursor = cursor
		}
		if !pullDone && *reply.HasMore && (reply.NextCursor == nil || *reply.NextCursor <= pullCursor) {
			return appErr("INVALID_CURSOR", "对方同步游标无效，已停止本轮同步。", 409)
		}
		pullDone = pullDone || !*reply.HasMore
		if pullDone {
			pullCursor = maxPeerCursor
		} else {
			pullCursor = *reply.NextCursor
		}
		if pushDone && pullDone {
			_, e = s.DB.Exec("UPDATE peer_connections SET last_sync_at=?,error=NULL WHERE id=? AND enabled=1", nowISO(), peer.ID)
			return e
		}
	}
	return appErr("SYNC_INCOMPLETE", "本轮同步尚未完成，稍后会从保留的数据继续。", 408)
}

type peerWorker struct {
	mu         sync.Mutex
	running    bool
	started    bool
	connection Object
}

func peerErrorMessage(e error) string {
	var app *AppError
	if errors.As(e, &app) {
		return app.Message
	}
	return "暂时连不上对方，本机日程已保存；连接恢复后自动重试。"
}
func (s *Server) peerConnectionStatus() Object {
	s.peerState.mu.Lock()
	defer s.peerState.mu.Unlock()
	copy := Object{}
	for k, v := range s.peerState.connection {
		copy[k] = v
	}
	return copy
}
func (s *Server) queuePeerSync() { go s.syncAllPeers() }
func (s *Server) syncAllPeers() {
	s.peerState.mu.Lock()
	if s.peerState.running {
		s.peerState.mu.Unlock()
		return
	}
	s.peerState.running = true
	s.peerState.mu.Unlock()
	defer func() { s.peerState.mu.Lock(); s.peerState.running = false; s.peerState.mu.Unlock() }()
	if _, e := loadIdentity(s.Store.DB); e != nil {
		return
	}
	peers, e := s.Store.allPeers()
	if e != nil {
		return
	}
	jobs := make(chan PeerConnection, len(peers))
	for _, p := range peers {
		jobs <- p
	}
	close(jobs)
	var workers sync.WaitGroup
	for n := 0; n < 4 && n < len(peers); n++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for p := range jobs {
				if e := s.Store.SyncPeer(p); e != nil {
					_ = s.Store.peerFailed(p.ID, peerErrorMessage(e))
				}
			}
		}()
	}
	workers.Wait()
}
func (s *Server) StartPeerWorker(ctx context.Context) {
	s.peerState.mu.Lock()
	if s.peerState.started {
		s.peerState.mu.Unlock()
		return
	}
	s.peerState.started = true
	s.peerState.mu.Unlock()
	go func() {
		timer := time.NewTicker(15 * time.Second)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				s.queuePeerSync()
			}
		}
	}()
}
func (s *Server) queueConnect(link, origin, owner string) error {
	if _, _, e := parsePeerLink(link); e != nil {
		return e
	}
	if origin != "" {
		if _, e := peerOrigin(origin); e != nil {
			return e
		}
	}
	s.peerState.mu.Lock()
	if s.peerState.connection["state"] == "connecting" {
		s.peerState.mu.Unlock()
		return appErr("PAIRING_BUSY", "正在配对，请稍候。", 409)
	}
	s.peerState.connection = Object{"state": "connecting"}
	s.peerState.mu.Unlock()
	go func() {
		e := s.Store.ConnectPeer(link, origin, owner)
		s.peerState.mu.Lock()
		if e == nil {
			s.peerState.connection = Object{"state": "connected"}
		} else {
			s.peerState.connection = Object{"state": "failed", "error": peerErrorMessage(e)}
		}
		s.peerState.mu.Unlock()
		if e == nil {
			s.queuePeerSync()
		}
	}()
	return nil
}
func (s *Store) removePeer(id string) error {
	peers, e := s.allPeers()
	if e != nil {
		return e
	}
	var peer *PeerConnection
	for _, p := range peers {
		if p.ID == id {
			copy := p
			peer = &copy
			break
		}
	}
	if e = s.disconnectPeer(id); e != nil {
		return e
	}
	if peer != nil {
		go func() {
			local, e := loadIdentity(s.DB)
			if e != nil {
				return
			}
			if _, e = verifyPeer(peer.Origin, local.ID, peer); e != nil {
				return
			}
			var session struct {
				Peer struct {
					ID string `json:"id"`
				} `json:"peer"`
			}
			if e = peerRequest(peer.Origin, "/api/devices/info", "GET", nil, peer.Token, &session); e != nil || !deviceUUIDPattern.MatchString(session.Peer.ID) {
				return
			}
			_ = peerRequest(peer.Origin, "/api/devices/"+session.Peer.ID, "DELETE", json.RawMessage(`{}`), peer.Token, nil)
		}()
	}
	return nil
}
func (s *Server) registerPeers() {
	s.peerState.connection = Object{"state": "idle"}
	s.route("POST /api/peers/connect", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireOwner(r)
		if e != nil {
			return nil, e
		}
		var input struct {
			URL         string `json:"url"`
			LocalOrigin string `json:"local_origin"`
		}
		if e = readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		if len(input.URL) > 2048 || input.URL == "" || len(input.LocalOrigin) > 512 {
			return nil, deviceValidation()
		}
		if e = s.Store.AssertActive(g); e != nil {
			return nil, e
		}
		local, e := buildConnectionInfo(r)
		if e != nil {
			return nil, e
		}
		origin := input.LocalOrigin
		if origin == "" {
			origin = local.CurrentOrigin
			if len(local.Candidates) > 0 {
				origin = local.Candidates[0]
			}
		}
		if e = s.queueConnect(input.URL, origin, g.ID); e != nil {
			return nil, e
		}
		return response{Status: 202, Data: Object{"queued": true}}, nil
	})
	s.route("POST /api/peers/sync", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireOwner(r)
		if e != nil {
			return nil, e
		}
		var input struct{}
		if e = readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		if e = s.Store.AssertActive(g); e != nil {
			return nil, e
		}
		s.queuePeerSync()
		return response{Status: 202, Data: Object{"queued": true}}, nil
	})
	s.route("DELETE /api/peers/{id}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireOwner(r)
		if e != nil {
			return nil, e
		}
		var input struct{}
		if e = readJSON(r, &input, 32768); e != nil {
			return nil, e
		}
		id := r.PathValue("id")
		if !deviceUUIDPattern.MatchString(id) {
			return nil, deviceValidation()
		}
		if e = s.Store.AssertActive(g); e != nil {
			return nil, e
		}
		if e = s.Store.removePeer(id); e != nil {
			return nil, e
		}
		return Object{"revoked": true, "id": id}, nil
	})
	s.route("GET /api/connection-info", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, e := s.requireAuth(r)
		if e != nil {
			return nil, e
		}
		if e = s.rateLimit("connection-info:"+g.SpaceID, 60, time.Minute); e != nil {
			return nil, e
		}
		return buildConnectionInfo(r)
	})
}

// Keep sql imported here to make the transaction guard's expected type explicit.
var _ DBTX = (*sql.Tx)(nil)
