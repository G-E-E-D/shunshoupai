package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

type SyncEvent struct {
	Title      string `json:"title"`
	StartLocal string `json:"start_local"`
	EndLocal   string `json:"end_local"`
	Timezone   string `json:"timezone"`
	Status     string `json:"status"`
}

func (e *SyncEvent) UnmarshalJSON(data []byte) error {
	type plain SyncEvent
	return strictRequired(data, (*plain)(e), "title", "start_local", "end_local", "timezone", "status")
}

type SyncMutation struct {
	MutationID  string     `json:"mutation_id"`
	ID          string     `json:"id"`
	BaseVersion int64      `json:"base_version"`
	Deleted     bool       `json:"deleted"`
	Event       *SyncEvent `json:"event,omitempty"`
}

func (m *SyncMutation) UnmarshalJSON(data []byte) error {
	type plain SyncMutation
	return strictRequired(data, (*plain)(m), "mutation_id", "id", "base_version", "deleted")
}

type SyncRequest struct {
	SpaceID   string         `json:"space_id,omitempty"`
	AccountID string         `json:"account_id,omitempty"`
	Mutations []SyncMutation `json:"mutations"`
	After     string         `json:"after,omitempty"`
}
type SyncResult struct {
	MutationID string `json:"mutation_id"`
	Status     string `json:"status"`
	Code       string `json:"code,omitempty"`
	Message    string `json:"message,omitempty"`
	Event      *Event `json:"event,omitempty"`
}

func normalizeSync(input *SyncRequest) error {
	input.SpaceID = strings.ToLower(input.SpaceID)
	input.AccountID = strings.ToLower(input.AccountID)
	input.After = strings.ToLower(input.After)
	if (input.SpaceID == "" && input.AccountID == "") || (input.SpaceID != "" && !validID(input.SpaceID)) || (input.AccountID != "" && !validID(input.AccountID)) || (input.SpaceID != "" && input.AccountID != "" && input.SpaceID != input.AccountID) || (input.After != "" && !validID(input.After)) || input.Mutations == nil || len(input.Mutations) > 20 {
		return invalidInput()
	}
	if input.SpaceID != "" {
		input.AccountID = input.SpaceID
	}
	seen := map[string]bool{}
	for i := range input.Mutations {
		m := &input.Mutations[i]
		m.ID = strings.ToLower(m.ID)
		m.MutationID = strings.ToLower(m.MutationID)
		if !validID(m.ID) || !validID(m.MutationID) || seen[m.MutationID] || m.BaseVersion < 0 || m.BaseVersion > 2147483646 || (m.Deleted && (m.Event != nil || m.BaseVersion < 1)) || (!m.Deleted && m.Event == nil) {
			return invalidInput()
		}
		seen[m.MutationID] = true
		if m.Event != nil {
			e := m.Event
			e.Title = strings.TrimSpace(e.Title)
			if e.Title == "" || textLength(e.Title) > 120 || e.Timezone != "Asia/Shanghai" || (e.Status != "planned" && e.Status != "completed") {
				return invalidInput()
			}
			if err := validateDuration(e.StartLocal, e.EndLocal); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *Store) Sync(space string, input SyncRequest, guards ...func(DBTX) error) (Object, error) {
	if err := normalizeSync(&input); err != nil {
		return nil, err
	}
	if input.AccountID != space {
		return nil, appErr("ACCOUNT_MISMATCH", "配对空间已改变，本机日程已保留，请重新核对设备。", 409)
	}
	author := s.DeviceAuthor()
	var result Object
	err := s.Transaction(func(tx *sql.Tx) error {
		for _, guard := range guards {
			if err := guard(tx); err != nil {
				return err
			}
		}
		results := make([]SyncResult, 0, len(input.Mutations))
		for _, m := range input.Mutations {
			item, err := syncMutation(tx, space, m, author)
			if err != nil {
				return err
			}
			results = append(results, item)
		}
		rows, err := eventRows(tx, "SELECT "+eventColumns+" FROM events WHERE user_id=? AND id>? ORDER BY id LIMIT 201", space, input.After)
		if err != nil {
			return err
		}
		more := len(rows) > 200
		if more {
			rows = rows[:200]
		}
		events := make([]Event, 0, len(rows))
		for _, row := range rows {
			events = append(events, row.Event)
		}
		var cursor any
		if more {
			cursor = events[len(events)-1].ID
		}
		result = Object{"results": results, "events": events, "has_more": more, "next_cursor": cursor}
		return nil
	})
	return result, err
}
func syncMutation(db DBTX, space string, m SyncMutation, author string) (SyncResult, error) {
	payloadHash := hash(string(canonicalJSON(m)))
	var oldHash, oldResult string
	err := db.QueryRow("SELECT payload_hash,result FROM sync_mutations WHERE user_id=? AND mutation_id=?", space, m.MutationID).Scan(&oldHash, &oldResult)
	if err == nil {
		if oldHash != payloadHash {
			return SyncResult{}, appErr("IDEMPOTENCY_CONFLICT", "同步内容已改变，请保留原请求核实保存结果。", 409)
		}
		var result SyncResult
		err = json.Unmarshal([]byte(oldResult), &result)
		return result, err
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return SyncResult{}, err
	}
	row, err := eventByID(db, space, m.ID)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return SyncResult{}, err
	}
	if _, err = db.Exec("SAVEPOINT legacy_mutation"); err != nil {
		return SyncResult{}, err
	}
	result := SyncResult{MutationID: m.MutationID, Status: "applied"}
	applyErr := func() error {
		if m.BaseVersion == 0 {
			var id string
			err := db.QueryRow("SELECT id FROM events WHERE id=?", m.ID).Scan(&id)
			if err == nil {
				return appErr("ID_CONFLICT", "这条日程编号已存在，手机内容已保留，请另存为新日程。", 409)
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return err
			}
		} else {
			if !exists {
				return appErr("NOT_FOUND", "这条日程已不存在，手机内容已保留。", 409)
			}
			if row.Version != m.BaseVersion || row.DeletedAt != nil {
				return appErr("VERSION_CONFLICT", "两台设备都修改了这条日程，手机内容已保留，请选择要使用的版本。", 409)
			}
		}
		if err := assertResolved(db, space, m.ID); err != nil {
			return err
		}
		if exists {
			if err := seedReplica(db, row, author); err != nil {
				return err
			}
		}
		now := nowISO()
		next := row
		if m.Event != nil {
			next.Title = m.Event.Title
			next.StartAt, _ = localISO(m.Event.StartLocal)
			next.EndAt, _ = localISO(m.Event.EndLocal)
			next.Status = m.Event.Status
		}
		next.UpdatedAt = now
		if m.Deleted {
			next.DeletedAt = &now
		}
		if m.BaseVersion == 0 {
			next.ID = m.ID
			next.UserID = space
			next.Timezone = "Asia/Shanghai"
			next.Version = 1
			next.CreatedAt = now
			_, err = db.Exec("INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at) VALUES(?,?,?,?,?,'Asia/Shanghai',?,1,?,?)", next.ID, space, next.Title, next.StartAt, next.EndAt, next.Status, now, now)
		} else {
			next.Version++
			_, err = db.Exec("UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=? WHERE user_id=? AND id=?", next.Title, next.StartAt, next.EndAt, next.Status, now, next.DeletedAt, space, m.ID)
		}
		if err != nil {
			return err
		}
		if err = changeReplica(db, next, author); err != nil {
			return err
		}
		result.Event = &next.Event
		return nil
	}()
	if applyErr != nil {
		var e *AppError
		if !errors.As(applyErr, &e) {
			return result, applyErr
		}
		if _, err = db.Exec("ROLLBACK TO legacy_mutation"); err != nil {
			return result, err
		}
		result.Status = "conflict"
		result.Code = e.Code
		result.Message = e.Message
		if exists {
			result.Event = &row.Event
		}
	}
	if _, err = db.Exec("RELEASE legacy_mutation"); err != nil {
		return result, err
	}
	_, err = db.Exec("INSERT INTO sync_mutations(user_id,mutation_id,payload_hash,result) VALUES(?,?,?,?)", space, m.MutationID, payloadHash, string(canonicalJSON(result)))
	return result, err
}

func (r *SyncRequest) UnmarshalJSON(data []byte) error {
	type plain SyncRequest
	return strictRequired(data, (*plain)(r), "mutations")
}
