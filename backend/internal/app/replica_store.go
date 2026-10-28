package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"time"
)

func initReplica(db *sql.DB) error {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS replica_records(id TEXT PRIMARY KEY REFERENCES events(id),space_id TEXT NOT NULL,heads TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS replica_receipts(revision_id TEXT PRIMARY KEY,event_id TEXT NOT NULL,head TEXT NOT NULL,digest TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS replica_revision_owners(revision_id TEXT PRIMARY KEY,event_id TEXT NOT NULL);`); err != nil {
		return err
	}
	var count int
	if err := db.QueryRow("SELECT count(*) FROM replica_revision_owners").Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return nil
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rows, err := tx.Query("SELECT event_id,head FROM replica_receipts")
	if err != nil {
		return err
	}
	type receipt struct{ id, body string }
	receipts := []receipt{}
	for rows.Next() {
		var r receipt
		if err = rows.Scan(&r.id, &r.body); err != nil {
			rows.Close()
			return err
		}
		receipts = append(receipts, r)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, r := range receipts {
		var head ReplicaHead
		if err = json.Unmarshal([]byte(r.body), &head); err != nil {
			return err
		}
		if err = reserveOwners(tx, r.id, head); err != nil {
			return err
		}
	}
	return tx.Commit()
}
func replicaRecord(db DBTX, space, id string) (*ReplicaRecord, error) {
	var body string
	err := db.QueryRow("SELECT heads FROM replica_records WHERE space_id=? AND id=?", space, id).Scan(&body)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	r := &ReplicaRecord{ID: id}
	err = json.Unmarshal([]byte(body), &r.Heads)
	return r, err
}
func reserveOwners(db DBTX, eventID string, head ReplicaHead) error {
	ids := append([]string{head.RevisionID}, head.Ancestors...)
	for _, id := range ids {
		var known string
		err := db.QueryRow("SELECT event_id FROM replica_revision_owners WHERE revision_id=?", id).Scan(&known)
		if err == nil && known != eventID {
			return appErr("INVALID_ANCESTRY", "同一版本编号不能属于不同日程，已停止合并。", 409)
		}
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if _, err = db.Exec("INSERT OR IGNORE INTO replica_revision_owners(revision_id,event_id) VALUES(?,?)", id, eventID); err != nil {
			return err
		}
	}
	return nil
}
func saveReplica(db DBTX, space string, record ReplicaRecord) error {
	for _, head := range record.Heads {
		content := string(canonicalJSON(head))
		digest := hash(content)
		var oldEvent, oldDigest string
		err := db.QueryRow("SELECT event_id,digest FROM replica_receipts WHERE revision_id=?", head.RevisionID).Scan(&oldEvent, &oldDigest)
		if err == nil {
			if oldEvent != record.ID || oldDigest != digest {
				return appErr("REVISION_COLLISION", "版本编号对应不同内容，已保留本机日程并停止合并。", 409)
			}
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err = reserveOwners(db, record.ID, head); err != nil {
			return err
		}
		for _, ancestor := range head.Ancestors {
			var eventID, body string
			err = db.QueryRow("SELECT event_id,head FROM replica_receipts WHERE revision_id=?", ancestor).Scan(&eventID, &body)
			if errors.Is(err, sql.ErrNoRows) {
				continue
			}
			if err != nil {
				return err
			}
			var old ReplicaHead
			if err = json.Unmarshal([]byte(body), &old); err != nil {
				return err
			}
			if eventID != record.ID || hasID(old.Ancestors, head.RevisionID) {
				return appErr("INVALID_ANCESTRY", "版本祖先关系与已保存历史不一致，已停止合并。", 409)
			}
			for _, id := range old.Ancestors {
				if !hasID(head.Ancestors, id) {
					return appErr("INVALID_ANCESTRY", "版本祖先关系与已保存历史不一致，已停止合并。", 409)
				}
			}
		}
		rows, err := db.Query("SELECT head FROM replica_receipts WHERE EXISTS(SELECT 1 FROM json_each(json_extract(head,'$.ancestors')) WHERE value=?)", head.RevisionID)
		if err != nil {
			return err
		}
		bodies := []string{}
		for rows.Next() {
			var body string
			if err = rows.Scan(&body); err != nil {
				rows.Close()
				return err
			}
			bodies = append(bodies, body)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		for _, body := range bodies {
			var child ReplicaHead
			if err = json.Unmarshal([]byte(body), &child); err != nil {
				return err
			}
			if hasID(head.Ancestors, child.RevisionID) {
				return appErr("INVALID_ANCESTRY", "迟到的版本与已保存的后续历史不一致，已停止合并。", 409)
			}
			for _, id := range head.Ancestors {
				if !hasID(child.Ancestors, id) {
					return appErr("INVALID_ANCESTRY", "迟到的版本与已保存的后续历史不一致，已停止合并。", 409)
				}
			}
		}
		if _, err = db.Exec("INSERT INTO replica_receipts(revision_id,event_id,head,digest) VALUES(?,?,?,?)", head.RevisionID, record.ID, content, digest); err != nil {
			return err
		}
	}
	_, err := db.Exec("INSERT INTO replica_records(id,space_id,heads) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET heads=excluded.heads", record.ID, space, string(canonicalJSON(record.Heads)))
	return err
}
func eventPayload(event Event) (ReplicaPayload, error) {
	start, err := isoToLocal(event.StartAt)
	if err != nil {
		return ReplicaPayload{}, err
	}
	end, err := isoToLocal(event.EndAt)
	if err != nil {
		return ReplicaPayload{}, err
	}
	return ReplicaPayload{event.Title, start, end, "Asia/Shanghai", event.Status, event.DeletedAt != nil}, nil
}
func seedReplica(db DBTX, row eventRow, author string) error {
	known, err := replicaRecord(db, row.UserID, row.ID)
	if err != nil || known != nil {
		return err
	}
	payload, err := eventPayload(row.Event)
	if err != nil {
		return err
	}
	changed, err := time.Parse(time.RFC3339Nano, row.UpdatedAt)
	if err != nil {
		return err
	}
	head, err := createRevision(nil, newID(), payload, author, changed.UnixMilli())
	if err != nil {
		return err
	}
	return saveReplica(db, row.UserID, ReplicaRecord{row.ID, []ReplicaHead{head}})
}
func changeReplica(db DBTX, row eventRow, author string) error {
	known, err := replicaRecord(db, row.UserID, row.ID)
	if err != nil {
		return err
	}
	var base []ReplicaHead
	if known != nil {
		base = known.Heads
	}
	payload, err := eventPayload(row.Event)
	if err != nil {
		return err
	}
	head, err := createRevision(base, newID(), payload, author, time.Now().UnixMilli())
	if err != nil {
		return err
	}
	return saveReplica(db, row.UserID, ReplicaRecord{row.ID, []ReplicaHead{head}})
}
func mergeRecord(db DBTX, space string, input ReplicaRecord, author string) error {
	record, err := normalizeRecord(input)
	if err != nil {
		return err
	}
	if len(canonicalJSON(record)) > 8*1024*1024 {
		return appErr("BODY_TOO_LARGE", "这条日程的历史过大，未丢弃任何版本。", 413)
	}
	row, err := scanEvent(db.QueryRow("SELECT "+eventColumns+" FROM events WHERE id=?", record.ID))
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if exists && row.UserID != space {
		return appErr("ID_CONFLICT", "日程编号已用于另一份本机日程，未覆盖原数据。", 409)
	}
	if exists {
		if err = seedReplica(db, row, author); err != nil {
			return err
		}
	}
	previous, err := replicaRecord(db, space, record.ID)
	if err != nil {
		return err
	}
	var oldHeads []ReplicaHead
	if previous != nil {
		oldHeads = previous.Heads
	}
	heads, err := MergeHeads(oldHeads, record.Heads)
	if err != nil {
		return err
	}
	merged := ReplicaRecord{record.ID, heads}
	projection := heads[0]
	for _, head := range heads {
		if !head.Payload.Deleted {
			projection = head
			break
		}
	}
	payload := projection.Payload
	start, _ := localISO(payload.StartLocal)
	end, _ := localISO(payload.EndLocal)
	now := nowISO()
	var deleted *string
	if payload.Deleted {
		deleted = &now
	}
	if !exists {
		if _, err = db.Exec("INSERT INTO events(id,user_id,title,start_at,end_at,timezone,status,version,created_at,updated_at,deleted_at) VALUES(?,?,?,?,?,'Asia/Shanghai',?,1,?,?,?)", record.ID, space, payload.Title, start, end, payload.Status, now, now, deleted); err != nil {
			return err
		}
	}
	// Persist even received heads already superseded, making revision reuse detectable.
	if err = saveReplica(db, space, record); err != nil {
		return err
	}
	if err = saveReplica(db, space, merged); err != nil {
		return err
	}
	if exists && string(canonicalJSON(oldHeads)) != string(canonicalJSON(heads)) {
		_, err = db.Exec("UPDATE events SET title=?,start_at=?,end_at=?,status=?,version=version+1,updated_at=?,deleted_at=? WHERE id=? AND user_id=?", payload.Title, start, end, payload.Status, now, deleted, record.ID, space)
	}
	return err
}
func replicaPage(db DBTX, space, after, author string) (Object, error) {
	rows, err := eventRows(db, "SELECT "+eventColumns+" FROM events WHERE user_id=? AND id>? ORDER BY id LIMIT 51", space, after)
	if err != nil {
		return nil, err
	}
	records := make([]ReplicaRecord, 0)
	bytes := 0
	for _, row := range rows {
		if err = seedReplica(db, row, author); err != nil {
			return nil, err
		}
		record, err := replicaRecord(db, space, row.ID)
		if err != nil {
			return nil, err
		}
		size := len(canonicalJSON(record))
		if size > 8*1024*1024 {
			return nil, appErr("BODY_TOO_LARGE", "日程历史过大，未截断任何内容。", 413)
		}
		if len(records) >= 50 || (len(records) > 0 && bytes+size > 2*1024*1024) {
			break
		}
		records = append(records, *record)
		bytes += size
	}
	more := len(records) < len(rows)
	var cursor any
	if more {
		cursor = records[len(records)-1].ID
	}
	return Object{"records": records, "has_more": more, "next_cursor": cursor}, nil
}
func (s *Store) ReplicaPage(space, after string) (Object, error) {
	return s.Exchange(space, nil, after)
}
func (s *Store) Exchange(space string, records []ReplicaRecord, after string, guards ...func(DBTX) error) (Object, error) {
	author := s.DeviceAuthor()
	var result Object
	err := s.Transaction(func(tx *sql.Tx) error {
		for _, guard := range guards {
			if err := guard(tx); err != nil {
				return err
			}
		}
		for _, record := range records {
			if err := mergeRecord(tx, space, record, author); err != nil {
				return err
			}
		}
		var err error
		result, err = replicaPage(tx, space, after, author)
		return err
	})
	return result, err
}
func (s *Store) ResolveReplica(space, id string, base int64, revisionID string, guards ...func(DBTX) error) (Object, error) {
	if !validID(id) || !validID(revisionID) || base < 1 {
		return nil, invalidInput()
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
			return appErr("NOT_FOUND", "日程不存在。", 404)
		}
		if err != nil {
			return err
		}
		if row.Version != base {
			return appErr("VERSION_CONFLICT", "又收到了新的修改，请查看后重新选择。", 409)
		}
		record, err := replicaRecord(tx, space, id)
		if err != nil {
			return err
		}
		if record == nil {
			return appErr("VERSION_CONFLICT", "选中的版本已改变，请重新加载。", 409)
		}
		var chosen *ReplicaHead
		for i := range record.Heads {
			if record.Heads[i].RevisionID == revisionID {
				chosen = &record.Heads[i]
				break
			}
		}
		if chosen == nil {
			return appErr("VERSION_CONFLICT", "选中的版本已改变，请重新加载。", 409)
		}
		next, err := createRevision(record.Heads, newID(), chosen.Payload, author, time.Now().UnixMilli())
		if err != nil {
			return err
		}
		if err = mergeRecord(tx, space, ReplicaRecord{id, []ReplicaHead{next}}, author); err != nil {
			return err
		}
		updated, err := eventByID(tx, space, id)
		if err != nil {
			return err
		}
		event, err := decorateEvent(tx, space, updated.Event)
		result = Object{"event": event}
		return err
	})
	return result, err
}
