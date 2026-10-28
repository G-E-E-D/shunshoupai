package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

type EventDraft struct {
	Title       string   `json:"title"`
	StartLocal  string   `json:"start_local"`
	EndLocal    string   `json:"end_local"`
	Timezone    string   `json:"timezone"`
	TimeSource  string   `json:"time_source"`
	Assumptions []string `json:"assumptions"`
}

func (d *EventDraft) UnmarshalJSON(data []byte) error {
	type plain EventDraft
	return strictRequired(data, (*plain)(d), "title", "start_local", "end_local", "timezone", "time_source", "assumptions")
}

type ReplicaConflict struct {
	RevisionID string         `json:"revision_id"`
	Author     string         `json:"author"`
	ChangedAt  int64          `json:"changed_at"`
	Payload    ReplicaPayload `json:"payload"`
}
type Event struct {
	ID               string            `json:"id"`
	Title            string            `json:"title"`
	StartAt          string            `json:"start_at"`
	EndAt            string            `json:"end_at"`
	Timezone         string            `json:"timezone"`
	Status           string            `json:"status"`
	Version          int64             `json:"version"`
	CreatedAt        string            `json:"created_at"`
	UpdatedAt        string            `json:"updated_at"`
	DeletedAt        *string           `json:"deleted_at"`
	ReplicaConflicts []ReplicaConflict `json:"replica_conflicts,omitempty"`
}
type eventRow struct {
	Event
	UserID string
}
type EventPatch struct {
	Title      *string `json:"title,omitempty"`
	StartLocal *string `json:"start_local,omitempty"`
	EndLocal   *string `json:"end_local,omitempty"`
	Status     *string `json:"status,omitempty"`
}

const eventColumns = "id,title,start_at,end_at,timezone,status,version,created_at,updated_at,deleted_at,user_id"

func scanEvent(row interface{ Scan(...any) error }) (eventRow, error) {
	var e eventRow
	err := row.Scan(&e.ID, &e.Title, &e.StartAt, &e.EndAt, &e.Timezone, &e.Status, &e.Version, &e.CreatedAt, &e.UpdatedAt, &e.DeletedAt, &e.UserID)
	return e, err
}
func eventByID(db DBTX, space, id string) (eventRow, error) {
	return scanEvent(db.QueryRow("SELECT "+eventColumns+" FROM events WHERE user_id=? AND id=?", space, id))
}
func eventRows(db DBTX, query string, args ...any) ([]eventRow, error) {
	rows, err := db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []eventRow{}
	for rows.Next() {
		e, err := scanEvent(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, e)
	}
	return result, rows.Err()
}
func validateDuration(start, end string) error {
	a, err := parseLocal(start)
	if err != nil {
		return err
	}
	b, err := parseLocal(end)
	if err != nil {
		return err
	}
	if !b.After(a) || b.Sub(a) > 24*time.Hour {
		return invalidInput()
	}
	return nil
}
func localISO(local string) (string, error) {
	t, err := parseLocal(local)
	if err != nil {
		return "", err
	}
	return t.UTC().Format("2006-01-02T15:04:05.000Z"), nil
}
func validateDraft(d *EventDraft) error {
	d.Title = strings.TrimSpace(d.Title)
	if d.Title == "" || textLength(d.Title) > 120 || d.Timezone != "Asia/Shanghai" || (d.TimeSource != "explicit" && d.TimeSource != "suggested" && d.TimeSource != "assumed") || d.Assumptions == nil || len(d.Assumptions) > 10 || (d.TimeSource == "assumed" && len(d.Assumptions) == 0) {
		return invalidInput()
	}
	for i, value := range d.Assumptions {
		value = strings.TrimSpace(value)
		if value == "" || textLength(value) > 240 {
			return invalidInput()
		}
		d.Assumptions[i] = value
	}
	return validateDuration(d.StartLocal, d.EndLocal)
}
func available(db DBTX, space, start, end, exclude string) error {
	var id string
	err := db.QueryRow("SELECT id FROM events WHERE user_id=? AND deleted_at IS NULL AND status='planned' AND id<>? AND start_at<? AND end_at>? LIMIT 1", space, exclude, end, start).Scan(&id)
	if err == nil {
		return appErr("TIME_CONFLICT", "时间已被其他日程占用，请调整时间后重试。", 409)
	}
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	return err
}
func decorateEvent(db DBTX, space string, event Event) (Event, error) {
	record, err := replicaRecord(db, space, event.ID)
	if err != nil {
		return event, err
	}
	if record == nil {
		return event, nil
	}
	variants, err := distinctHeads(record.Heads)
	if err != nil {
		return event, err
	}
	if len(variants) > 1 {
		for _, h := range variants {
			event.ReplicaConflicts = append(event.ReplicaConflicts, ReplicaConflict{h.RevisionID, h.Author, h.ChangedAt, h.Payload})
		}
	}
	return event, nil
}
func (s *Store) Event(space, id string) (Event, error) {
	row, err := eventByID(s.DB, space, id)
	if errors.Is(err, sql.ErrNoRows) {
		return Event{}, appErr("NOT_FOUND", "日程不存在或已删除。", 404)
	}
	if err != nil {
		return Event{}, err
	}
	return decorateEvent(s.DB, space, row.Event)
}
func (s *Store) ListEvents(space, from, to string) ([]Event, error) {
	rows, err := eventRows(s.DB, "SELECT "+eventColumns+" FROM events WHERE user_id=? AND deleted_at IS NULL AND start_at<? AND end_at>? ORDER BY start_at,id LIMIT 501", space, to, from)
	if err != nil {
		return nil, err
	}
	if len(rows) > 500 {
		return nil, appErr("TOO_MANY_EVENTS", "这个时间范围内日程过多，请缩小查询范围。", 400)
	}
	result := make([]Event, 0, len(rows))
	for _, row := range rows {
		e, err := decorateEvent(s.DB, space, row.Event)
		if err != nil {
			return nil, err
		}
		result = append(result, e)
	}
	return result, nil
}
func (s *Store) Commit(space, requestID string, drafts []EventDraft, guards ...func(DBTX) error) (Object, error) {
	if !validID(requestID) || len(drafts) < 1 || len(drafts) > 5 {
		return nil, invalidInput()
	}
	for i := range drafts {
		if err := validateDraft(&drafts[i]); err != nil {
			return nil, err
		}
	}
	author := s.DeviceAuthor()
	var result Object
	err := s.Transaction(func(tx *sql.Tx) error {
		for _, guard := range guards {
			if err := guard(tx); err != nil {
				return err
			}
		}
		payloadHash := hash(string(canonicalJSON(drafts)))
		var oldHash, oldResult string
		err := tx.QueryRow("SELECT payload_hash,result FROM mutation_requests WHERE user_id=? AND request_id=?", space, requestID).Scan(&oldHash, &oldResult)
		if err == nil {
			if oldHash != payloadHash {
				return appErr("IDEMPOTENCY_CONFLICT", "这次保存的内容已改变，请重新确认。", 409)
			}
			var events []Event
			if err = json.Unmarshal([]byte(oldResult), &events); err != nil {
				return err
			}
			result = Object{"events": events, "replayed": true}
			return nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		now := nowISO()
		events := make([]Event, 0, len(drafts))
		for _, draft := range drafts {
			start, _ := localISO(draft.StartLocal)
			end, _ := localISO(draft.EndLocal)
			if err := available(tx, space, start, end, ""); err != nil {
				return err
			}
			e := Event{ID: newID(), Title: draft.Title, StartAt: start, EndAt: end, Timezone: draft.Timezone, Status: "planned", Version: 1, CreatedAt: now, UpdatedAt: now}
			if _, err := tx.Exec("INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)", e.ID, space, e.Title, e.StartAt, e.EndAt, e.Timezone, e.Status, e.Version, now, now); err != nil {
				return err
			}
			if err := seedReplica(tx, eventRow{e, space}, author); err != nil {
				return err
			}
			events = append(events, e)
		}
		if _, err := tx.Exec("INSERT INTO mutation_requests(user_id,request_id,payload_hash,result,created_at) VALUES(?,?,?,?,?)", space, requestID, payloadHash, string(canonicalJSON(events)), now); err != nil {
			return err
		}
		result = Object{"events": events, "replayed": false}
		return nil
	})
	return result, err
}
func validatePatch(p *EventPatch) error {
	if p.Title != nil {
		title := strings.TrimSpace(*p.Title)
		if title == "" || textLength(title) > 120 {
			return invalidInput()
		}
		p.Title = &title
	}
	if p.Status != nil && *p.Status != "planned" && *p.Status != "completed" {
		return invalidInput()
	}
	for _, value := range []*string{p.StartLocal, p.EndLocal} {
		if value != nil {
			if _, err := parseLocal(*value); err != nil {
				return err
			}
		}
	}
	return nil
}
func assertResolved(db DBTX, space, id string) error {
	record, err := replicaRecord(db, space, id)
	if err != nil {
		return err
	}
	if record == nil {
		return nil
	}
	heads, err := distinctHeads(record.Heads)
	if err != nil {
		return err
	}
	if len(heads) > 1 {
		return appErr("REPLICA_CONFLICT", "这条日程有不同设备的修改，请先选择要保留的版本。", 409)
	}
	return nil
}
func (s *Store) Mutate(space, id string, base int64, patch EventPatch, deleting bool, guards ...func(DBTX) error) (Object, error) {
	if !validID(id) || base <= 0 {
		return nil, invalidInput()
	}
	if err := validatePatch(&patch); err != nil {
		return nil, err
	}
	author := s.DeviceAuthor()
	var result Object
	err := s.Transaction(func(tx *sql.Tx) error {
		for _, guard := range guards {
			if err := guard(tx); err != nil {
				return err
			}
		}
		row, err := eventByID(tx, space, id)
		if errors.Is(err, sql.ErrNoRows) {
			return appErr("NOT_FOUND", "日程不存在或已删除。", 404)
		}
		if err != nil {
			return err
		}
		if err = assertResolved(tx, space, id); err != nil {
			return err
		}
		if err = seedReplica(tx, row, author); err != nil {
			return err
		}
		if row.DeletedAt != nil {
			if deleting && (row.Version == base || row.Version == base+1) {
				result = Object{"event": row.Event}
				return nil
			}
			return appErr("NOT_FOUND", "日程不存在或已删除。", 404)
		}
		if row.Version != base {
			return appErr("VERSION_CONFLICT", "这条日程已在另一端更新，请刷新后重新确认。", 409)
		}
		next := row
		if patch.Title != nil {
			next.Title = *patch.Title
		}
		if patch.StartLocal != nil {
			next.StartAt, _ = localISO(*patch.StartLocal)
		}
		if patch.EndLocal != nil {
			next.EndAt, _ = localISO(*patch.EndLocal)
		}
		if patch.Status != nil {
			next.Status = *patch.Status
		}
		start, _ := time.Parse(time.RFC3339Nano, next.StartAt)
		end, _ := time.Parse(time.RFC3339Nano, next.EndAt)
		if !end.After(start) || end.Sub(start) > 24*time.Hour {
			return invalidInput()
		}
		if !deleting && next.Status == "planned" && (row.Status != "planned" || next.StartAt != row.StartAt || next.EndAt != row.EndAt) {
			if err := available(tx, space, next.StartAt, next.EndAt, id); err != nil {
				return err
			}
		}
		next.Version++
		next.UpdatedAt = nowISO()
		if deleting {
			next.DeletedAt = &next.UpdatedAt
		}
		if _, err = tx.Exec("UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=?,updated_at=?,deleted_at=? WHERE user_id=? AND id=?", next.Title, next.StartAt, next.EndAt, next.Status, next.Version, next.UpdatedAt, next.DeletedAt, space, id); err != nil {
			return err
		}
		if err = changeReplica(tx, next, author); err != nil {
			return err
		}
		result = Object{"event": next.Event}
		return nil
	})
	return result, err
}
