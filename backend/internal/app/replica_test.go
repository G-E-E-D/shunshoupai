package app

import (
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
)

func replicaCode(t *testing.T, err error, want string) {
	t.Helper()
	var app *AppError
	if !errors.As(err, &app) || app.Code != want {
		t.Fatalf("expected %s, got %v", want, err)
	}
}
func TestSharedReplicaFixtures(t *testing.T) {
	body, err := os.ReadFile("../../../android/app/src/androidTest/assets/replica-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures struct {
		Valid []struct {
			Name                      string
			Local, Incoming, Expected []ReplicaHead
			Distinct                  int
		}
		Invalid []struct {
			Name            string
			Local, Incoming []ReplicaHead
			Code            string
		}
	}
	if err = json.Unmarshal(body, &fixtures); err != nil {
		t.Fatal(err)
	}
	for _, item := range fixtures.Valid {
		t.Run(item.Name, func(t *testing.T) {
			merged, err := MergeHeads(item.Local, item.Incoming)
			if err != nil {
				t.Fatal(err)
			}
			if !reflect.DeepEqual(merged, item.Expected) {
				t.Fatalf("heads differ: %s", canonicalJSON(merged))
			}
			distinct, err := distinctHeads(merged)
			if err != nil || len(distinct) != item.Distinct {
				t.Fatalf("distinct %d: %v", len(distinct), err)
			}
		})
	}
	for _, item := range fixtures.Invalid {
		t.Run(item.Name, func(t *testing.T) { _, err := MergeHeads(item.Local, item.Incoming); replicaCode(t, err, item.Code) })
	}
}
func replicaDevice(t *testing.T, name string) (*Store, string) {
	t.Helper()
	store, err := OpenStore(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })
	owner, err := store.Bootstrap(name, "test", "", "", "")
	if err != nil {
		t.Fatal(err)
	}
	return store, owner.Grant.SpaceID
}
func replicaDraft() EventDraft {
	return EventDraft{"原日程", "2031-01-01T09:00:00", "2031-01-01T10:00:00", "Asia/Shanghai", "explicit", []string{}}
}
func replicaCreate(t *testing.T, s *Store, space string) Event {
	t.Helper()
	result, err := s.Commit(space, newID(), []EventDraft{replicaDraft()})
	if err != nil {
		t.Fatal(err)
	}
	return result["events"].([]Event)[0]
}
func replicaRecords(t *testing.T, s *Store, space string) []ReplicaRecord {
	t.Helper()
	page, err := s.ReplicaPage(space, "")
	if err != nil {
		t.Fatal(err)
	}
	return page["records"].([]ReplicaRecord)
}
func replicaSend(t *testing.T, a *Store, as string, b *Store, bs string) {
	t.Helper()
	_, err := b.Exchange(bs, replicaRecords(t, a, as), "")
	if err != nil {
		t.Fatal(err)
	}
}
func TestReplicaTriangleConflictAndExplicitResolution(t *testing.T) {
	a, as := replicaDevice(t, "A")
	b, bs := replicaDevice(t, "B")
	c, cs := replicaDevice(t, "C")
	event := replicaCreate(t, a, as)
	replicaSend(t, a, as, b, bs)
	replicaSend(t, b, bs, c, cs)
	title := "手机修改"
	if _, err := b.Mutate(bs, event.ID, 1, EventPatch{Title: &title}, false); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Mutate(cs, event.ID, 1, EventPatch{}, true); err != nil {
		t.Fatal(err)
	}
	replicaSend(t, b, bs, a, as)
	replicaSend(t, c, cs, a, as)
	replicaSend(t, a, as, b, bs)
	replicaSend(t, b, bs, c, cs)
	before := replicaRecords(t, a, as)
	seen, err := a.Event(as, event.ID)
	if err != nil || len(seen.ReplicaConflicts) != 2 || seen.DeletedAt != nil {
		t.Fatalf("missing conflict: %#v %v", seen, err)
	}
	for range 3 {
		replicaSend(t, a, as, b, bs)
		replicaSend(t, b, bs, c, cs)
		replicaSend(t, c, cs, a, as)
	}
	if !reflect.DeepEqual(before, replicaRecords(t, a, as)) {
		t.Fatal("non-idempotent triangle")
	}
	_, err = a.Mutate(as, event.ID, seen.Version, EventPatch{Title: &title}, false)
	replicaCode(t, err, "REPLICA_CONFLICT")
	var choice string
	for _, head := range seen.ReplicaConflicts {
		if head.Payload.Deleted {
			choice = head.RevisionID
		}
	}
	if _, err = a.ResolveReplica(as, event.ID, seen.Version, choice); err != nil {
		t.Fatal(err)
	}
	replicaSend(t, a, as, b, bs)
	replicaSend(t, b, bs, c, cs)
	for _, device := range []struct {
		s     *Store
		space string
	}{{a, as}, {b, bs}, {c, cs}} {
		e, err := device.s.Event(device.space, event.ID)
		if err != nil || e.DeletedAt == nil || len(e.ReplicaConflicts) > 0 {
			t.Fatalf("unresolved: %#v %v", e, err)
		}
	}
}
func TestReplicaReceiptsRollbackAndLateAncestors(t *testing.T) {
	a, space := replicaDevice(t, "A")
	event := replicaCreate(t, a, space)
	original := replicaRecords(t, a, space)[0]
	title := "后续修改"
	if _, err := a.Mutate(space, event.ID, 1, EventPatch{Title: &title}, false); err != nil {
		t.Fatal(err)
	}
	injected := ReplicaRecord{newID(), []ReplicaHead{original.Heads[0]}}
	injected.Heads[0].RevisionID = newID()
	tampered := original
	tampered.Heads = append([]ReplicaHead{}, original.Heads...)
	tampered.Heads[0].Author = "篡改"
	before := replicaRecords(t, a, space)
	_, err := a.Exchange(space, []ReplicaRecord{injected, tampered}, "")
	replicaCode(t, err, "REVISION_COLLISION")
	if !reflect.DeepEqual(before, replicaRecords(t, a, space)) {
		t.Fatal("partial exchange persisted")
	}
	_, err = a.Event(space, injected.ID)
	replicaCode(t, err, "NOT_FOUND")
	missing, hidden := newID(), newID()
	head := before[0].Heads[0]
	child, err := createRevision([]ReplicaHead{head}, newID(), head.Payload, "B", 1)
	if err != nil {
		t.Fatal(err)
	}
	child.Ancestors = append(child.Ancestors, missing)
	child, err = normalizeHead(child)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.Exchange(space, []ReplicaRecord{{event.ID, []ReplicaHead{child}}}, ""); err != nil {
		t.Fatal(err)
	}
	late := head
	late.RevisionID = missing
	late.Ancestors = []string{}
	_, err = a.Exchange(space, []ReplicaRecord{{newID(), []ReplicaHead{late}}}, "")
	replicaCode(t, err, "INVALID_ANCESTRY")
	late.Ancestors = []string{hidden}
	_, err = a.Exchange(space, []ReplicaRecord{{event.ID, []ReplicaHead{late}}}, "")
	replicaCode(t, err, "INVALID_ANCESTRY")
	late.Ancestors = []string{}
	if _, err = a.Exchange(space, []ReplicaRecord{{event.ID, []ReplicaHead{late}}}, ""); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(replicaRecords(t, a, space)[0].Heads, []ReplicaHead{child}) {
		t.Fatal("late ancestor changed frontier")
	}
}
func TestReplicaUnseenHeadBlocksStaleResolution(t *testing.T) {
	a, space := replicaDevice(t, "A")
	event := replicaCreate(t, a, space)
	backup := replicaRecords(t, a, space)[0]
	title := "新内容"
	if _, err := a.Mutate(space, event.ID, 1, EventPatch{Title: &title}, false); err != nil {
		t.Fatal(err)
	}
	seen, err := a.Event(space, event.ID)
	if err != nil {
		t.Fatal(err)
	}
	payload := backup.Heads[0].Payload
	payload.Title = "备份后修改"
	restored, err := createRevision(backup.Heads, newID(), payload, "旧备份", -1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = a.Exchange(space, []ReplicaRecord{{event.ID, []ReplicaHead{restored}}}, ""); err != nil {
		t.Fatal(err)
	}
	_, err = a.ResolveReplica(space, event.ID, seen.Version, backup.Heads[0].RevisionID)
	replicaCode(t, err, "VERSION_CONFLICT")
}
func TestCanonicalJSONMatchesJavaScript(t *testing.T) {
	value := []string{"<标题>&\u2028\u2029", `\u2028`, "\n\t\""}
	want := "[\"<标题>&\u2028\u2029\",\"\\\\u2028\",\"\\n\\t\\\"\"]"
	if string(canonicalJSON(value)) != want {
		t.Fatalf("canonical mismatch %q", canonicalJSON(value))
	}
}
func TestReplicaSchemaRejectsMissingAndUnknownWireFields(t *testing.T) {
	p := ReplicaPayload{"会议", "2030-01-01T09:00:00", "2030-01-01T10:00:00", "Asia/Shanghai", "planned", false}
	h, err := createRevision(nil, newID(), p, "A", 0)
	if err != nil {
		t.Fatal(err)
	}
	body := string(canonicalJSON(h))
	for _, value := range []string{strings.Replace(body, `,"changed_at":0`, "", 1), strings.Replace(body, `,"deleted":false`, "", 1), strings.Replace(body, `"ancestors":[]`, `"ancestors":null`, 1), strings.Replace(body, `"author":"A"`, `"author":"A","extra":1`, 1)} {
		var parsed ReplicaHead
		if json.Unmarshal([]byte(value), &parsed) == nil {
			t.Fatalf("accepted invalid %s", value)
		}
	}
}
