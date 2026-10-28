package app

import (
	"net/http"
	"strings"
	"time"
)

type exchangeRequest struct {
	DeviceID string          `json:"device_id"`
	Records  []ReplicaRecord `json:"records"`
	After    string          `json:"after,omitempty"`
}

func (x *exchangeRequest) UnmarshalJSON(data []byte) error {
	type plain exchangeRequest
	return strictRequired(data, (*plain)(x), "device_id", "records")
}
func (s *Server) registerEvents() {
	s.route("GET /api/events", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		from, err := time.Parse(time.RFC3339Nano, r.URL.Query().Get("from"))
		if err != nil {
			return nil, appErr("INVALID_WINDOW", "请选择不超过 93 天的有效时间范围。", 400)
		}
		to, err := time.Parse(time.RFC3339Nano, r.URL.Query().Get("to"))
		if err != nil || !to.After(from) || to.Sub(from) > 93*24*time.Hour {
			return nil, appErr("INVALID_WINDOW", "请选择不超过 93 天的有效时间范围。", 400)
		}
		events, err := s.Store.ListEvents(g.SpaceID, from.UTC().Format("2006-01-02T15:04:05.000Z"), to.UTC().Format("2006-01-02T15:04:05.000Z"))
		return Object{"events": events}, err
	})
	s.route("GET /api/events/{id}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		id := r.PathValue("id")
		if !validID(id) {
			return nil, invalidInput()
		}
		event, err := s.Store.Event(g.SpaceID, id)
		return Object{"event": event}, err
	})
	s.route("POST /api/events/commit", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		if err = s.rateLimit("write:"+g.SpaceID, 120, time.Minute); err != nil {
			return nil, err
		}
		var input struct {
			RequestID string       `json:"request_id"`
			Events    []EventDraft `json:"events"`
		}
		if err = readJSON(r, &input, 32768); err != nil {
			return nil, err
		}
		return s.Store.Commit(g.SpaceID, input.RequestID, input.Events, func(db DBTX) error { return assertGrantActive(db, g) })
	})
	s.route("PATCH /api/events/{id}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		if err = s.rateLimit("write:"+g.SpaceID, 120, time.Minute); err != nil {
			return nil, err
		}
		var input eventPatchRequest
		if err = readJSON(r, &input, 32768); err != nil {
			return nil, err
		}
		if input.Title == nil && input.StartLocal == nil && input.EndLocal == nil && input.Status == nil {
			return nil, invalidInput()
		}
		return s.Store.Mutate(g.SpaceID, r.PathValue("id"), input.BaseVersion, input.EventPatch, false, func(db DBTX) error { return assertGrantActive(db, g) })
	})
	s.route("DELETE /api/events/{id}", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		if err = s.rateLimit("write:"+g.SpaceID, 120, time.Minute); err != nil {
			return nil, err
		}
		var input struct {
			BaseVersion int64 `json:"base_version"`
		}
		if err = readJSON(r, &input, 32768); err != nil {
			return nil, err
		}
		return s.Store.Mutate(g.SpaceID, r.PathValue("id"), input.BaseVersion, EventPatch{}, true, func(db DBTX) error { return assertGrantActive(db, g) })
	})
	s.route("POST /api/events/{id}/resolve", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		var input struct {
			BaseVersion int64  `json:"base_version"`
			RevisionID  string `json:"revision_id"`
		}
		if err = readJSON(r, &input, 32768); err != nil {
			return nil, err
		}
		return s.Store.ResolveReplica(g.SpaceID, r.PathValue("id"), input.BaseVersion, input.RevisionID, func(db DBTX) error { return assertGrantActive(db, g) })
	})
	s.route("POST /api/events/sync", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		if err = s.rateLimit("sync:"+g.SpaceID, 120, time.Minute); err != nil {
			return nil, err
		}
		var input SyncRequest
		if err = readJSON(r, &input, 32768); err != nil {
			return nil, err
		}
		if err = normalizeSync(&input); err != nil {
			return nil, err
		}
		if input.AccountID != g.SpaceID {
			return nil, appErr("SPACE_MISMATCH", "配对日程空间不一致，本机修改已保留，请重新核对设备。", 409)
		}
		result, err := s.Store.Sync(g.SpaceID, input, func(db DBTX) error { return assertGrantActive(db, g) })
		if err != nil {
			return nil, err
		}
		context, err := s.Store.DeviceContext()
		if err != nil {
			return nil, err
		}
		for key, value := range context {
			result[key] = value
		}
		return result, nil
	})
	s.route("POST /api/replica/exchange", func(w http.ResponseWriter, r *http.Request) (any, error) {
		g, err := s.requireAuth(r)
		if err != nil {
			return nil, err
		}
		var input exchangeRequest
		if err = readJSON(r, &input, 16*1024*1024); err != nil {
			return nil, err
		}
		input.DeviceID = strings.ToLower(input.DeviceID)
		input.After = strings.ToLower(input.After)
		if !validID(input.DeviceID) || len(input.Records) > 50 || (input.After != "" && !validID(input.After)) {
			return nil, invalidInput()
		}
		if input.DeviceID != g.ClientID {
			return nil, appErr("DEVICE_MISMATCH", "同步设备与授权不匹配。", 403)
		}
		result, err := s.Store.Exchange(g.SpaceID, input.Records, input.After, func(db DBTX) error { return assertGrantActive(db, g) })
		if err != nil {
			return nil, err
		}
		context, err := s.Store.DeviceContext()
		if err != nil {
			return nil, err
		}
		for key, value := range context {
			result[key] = value
		}
		return result, nil
	})
}

// Keep explicit null distinct from omitted optional patch fields.
type eventPatchRequest struct {
	BaseVersion int64 `json:"base_version"`
	EventPatch
}

func (p *eventPatchRequest) UnmarshalJSON(data []byte) error {
	type plain eventPatchRequest
	return strictRequired(data, (*plain)(p), "base_version")
}
