package app

import (
	"database/sql"
	"path/filepath"
	"reflect"
	"testing"
)

func syncCreate(title string) SyncMutation {
	return SyncMutation{MutationID: newID(), ID: newID(), Event: &SyncEvent{title, "2031-01-01T09:00:00", "2031-01-01T10:00:00", "Asia/Shanghai", "planned"}}
}
func TestLegacySyncReplayConflictIsolationAndRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.sqlite")
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	owner, err := store.Bootstrap("A", "test", "", "", "")
	if err != nil {
		t.Fatal(err)
	}
	space := owner.Grant.SpaceID
	original := syncCreate("离线日程")
	input := SyncRequest{AccountID: space, Mutations: []SyncMutation{original}}
	first, err := store.Sync(space, input)
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Sync(space, input)
	if err != nil || !reflect.DeepEqual(first, second) {
		t.Fatalf("replay differs %v", err)
	}
	if err = store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	reopened, err := store.Sync(space, input)
	if err != nil || !reflect.DeepEqual(first, reopened) {
		t.Fatalf("restart replay differs %v", err)
	}
	title := "电脑已修改"
	_, err = store.Mutate(space, original.ID, 1, EventPatch{Title: &title}, false)
	if err != nil {
		t.Fatal(err)
	}
	stale := original
	stale.MutationID = newID()
	stale.BaseVersion = 1
	independent := syncCreate("另一安排")
	mixed, err := store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{stale, independent}})
	if err != nil {
		t.Fatal(err)
	}
	results := mixed["results"].([]SyncResult)
	if results[0].Code != "VERSION_CONFLICT" || results[0].Event.Title != title || results[1].Status != "applied" {
		t.Fatalf("bad results %#v", results)
	}
	changed := original
	changed.Event = &SyncEvent{"篡改请求", original.Event.StartLocal, original.Event.EndLocal, "Asia/Shanghai", "planned"}
	injected := syncCreate("不应部分保存")
	_, err = store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{injected, changed}})
	replicaCode(t, err, "IDEMPOTENCY_CONFLICT")
	_, err = store.Event(space, injected.ID)
	replicaCode(t, err, "NOT_FOUND")
	deleted, err := store.Mutate(space, original.ID, 2, EventPatch{}, true)
	if err != nil {
		t.Fatal(err)
	}
	if deleted["event"].(Event).DeletedAt == nil {
		t.Fatal("missing tombstone")
	}
	_, err = store.Sync(space, SyncRequest{SpaceID: newID(), Mutations: []SyncMutation{}})
	replicaCode(t, err, "ACCOUNT_MISMATCH")
}
func TestReplicaPaginationAndIdenticalRoots(t *testing.T) {
	a, space := replicaDevice(t, "A")
	event := replicaCreate(t, a, space)
	initial := replicaRecords(t, a, space)[0]
	same, err := createRevision(nil, newID(), initial.Heads[0].Payload, "迁移副本", 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.Exchange(space, []ReplicaRecord{{event.ID, []ReplicaHead{same}}}, ""); err != nil {
		t.Fatal(err)
	}
	seen, err := a.Event(space, event.ID)
	if err != nil || len(seen.ReplicaConflicts) > 0 {
		t.Fatalf("false visible conflict %v", err)
	}
	if len(replicaRecords(t, a, space)[0].Heads) != 2 {
		t.Fatal("equal root was pruned")
	}
	title := "共同后续"
	if _, err = a.Mutate(space, event.ID, seen.Version, EventPatch{Title: &title}, false); err != nil {
		t.Fatal(err)
	}
	if len(replicaRecords(t, a, space)[0].Heads[0].Ancestors) != 2 {
		t.Fatal("lost hidden causal root")
	}
	for range 3 {
		mutations := make([]SyncMutation, 20)
		for i := range mutations {
			mutations[i] = syncCreate("重叠日程")
		}
		if _, err = a.Sync(space, SyncRequest{SpaceID: space, Mutations: mutations}); err != nil {
			t.Fatal(err)
		}
	}
	page, err := a.ReplicaPage(space, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page["records"].([]ReplicaRecord)) != 50 || page["has_more"] != true {
		t.Fatalf("bad page %#v", page)
	}
	next, err := a.ReplicaPage(space, page["next_cursor"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if len(next["records"].([]ReplicaRecord)) != 11 || next["has_more"] != false {
		t.Fatal("lost page")
	}
	b, bs := replicaDevice(t, "B")
	for _, p := range []Object{page, next} {
		if _, err = b.Exchange(bs, p["records"].([]ReplicaRecord), ""); err != nil {
			t.Fatal(err)
		}
	}
	events, err := b.ListEvents(bs, "2031-01-01T00:00:00.000Z", "2031-01-02T00:00:00.000Z")
	if err != nil || len(events) != 61 {
		t.Fatalf("lost overlapping offline appointments %d %v", len(events), err)
	}
}
func TestWriteAuthorizationGuardIsAtomic(t *testing.T) {
	store, space := replicaDevice(t, "A")
	event := replicaCreate(t, store, space)
	record := replicaRecords(t, store, space)[0]
	denied := func(DBTX) error { return appErr("UNAUTHORIZED", "已撤销", 401) }
	_, err := store.Commit(space, newID(), []EventDraft{replicaDraft()}, denied)
	replicaCode(t, err, "UNAUTHORIZED")
	title := "不应保存"
	_, err = store.Mutate(space, event.ID, 1, EventPatch{Title: &title}, false, denied)
	replicaCode(t, err, "UNAUTHORIZED")
	_, err = store.Exchange(space, []ReplicaRecord{record}, "", denied)
	replicaCode(t, err, "UNAUTHORIZED")
	_, err = store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{syncCreate("不应保存")}}, denied)
	replicaCode(t, err, "UNAUTHORIZED")
	_, err = store.ResolveReplica(space, event.ID, 1, record.Heads[0].RevisionID, denied)
	replicaCode(t, err, "UNAUTHORIZED")
	var count int
	if err = store.DB.QueryRow("SELECT count(*) FROM events").Scan(&count); err != nil || count != 1 {
		t.Fatal("unauthorized write persisted")
	}
	seen, err := store.Event(space, event.ID)
	if err != nil || seen.Title != event.Title || seen.Version != 1 {
		t.Fatal("unauthorized mutation persisted")
	}
	// The guard receives the active transaction, not a second pooled connection.
	accepted := func(db DBTX) error { var one int; return db.QueryRow("SELECT 1").Scan(&one) }
	if _, err = store.Mutate(space, event.ID, 1, EventPatch{Title: &title}, false, accepted); err != nil {
		t.Fatal(err)
	}
}
func TestSyncCrossSpaceAndValidation(t *testing.T) {
	store, space := replicaDevice(t, "A")
	other := newID()
	_, err := store.DB.Exec("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,?,?,?)", other, other+"@local.invalid", "device-only", nowISO())
	if err != nil {
		t.Fatal(err)
	}
	original := syncCreate("私有安排")
	if _, err = store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{original}}); err != nil {
		t.Fatal(err)
	}
	response, err := store.Sync(other, SyncRequest{SpaceID: other, Mutations: []SyncMutation{original}})
	if err != nil {
		t.Fatal(err)
	}
	result := response["results"].([]SyncResult)[0]
	if result.Code != "ID_CONFLICT" || result.Event != nil {
		t.Fatal("cross-space event leaked")
	}
	malformed := original
	malformed.MutationID = newID()
	malformed.Event = &SyncEvent{"无效", "2031-02-30T09:00:00", "2031-03-01T10:00:00", "Asia/Shanghai", "planned"}
	_, err = store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{syncCreate("未保存"), malformed}})
	replicaCode(t, err, "VALIDATION_ERROR")
	_, err = store.Sync(space, SyncRequest{SpaceID: space, Mutations: []SyncMutation{original, original}})
	replicaCode(t, err, "VALIDATION_ERROR")
	var count int
	if err = store.DB.QueryRow("SELECT count(*) FROM events").Scan(&count); err != nil || count != 1 {
		t.Fatal("invalid batch partly applied")
	}
}

var _ *sql.Tx
