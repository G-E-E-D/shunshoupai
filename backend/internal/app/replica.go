package app

import (
	"bytes"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"unicode/utf16"
)

const maxHeads = 32
const maxAncestors = 2048

var uuidPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func validID(id string) bool      { return uuidPattern.MatchString(id) }
func textLength(value string) int { return len(utf16.Encode([]rune(value))) }
func invalidInput() error {
	return appErr("VALIDATION_ERROR", "请检查输入的字段与时间。", 400)
}

// Order matches JSON.stringify in v2 so existing durable receipt hashes survive.
type ReplicaPayload struct {
	Title      string `json:"title"`
	StartLocal string `json:"start_local"`
	EndLocal   string `json:"end_local"`
	Timezone   string `json:"timezone"`
	Status     string `json:"status"`
	Deleted    bool   `json:"deleted"`
}
type ReplicaHead struct {
	RevisionID string         `json:"revision_id"`
	Ancestors  []string       `json:"ancestors"`
	Payload    ReplicaPayload `json:"payload"`
	Author     string         `json:"author"`
	ChangedAt  int64          `json:"changed_at"`
}
type ReplicaRecord struct {
	ID    string        `json:"id"`
	Heads []ReplicaHead `json:"heads"`
}

func strictRequired(data []byte, target any, keys ...string) error {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil || fields == nil {
		return fmt.Errorf("invalid object")
	}
	for key, value := range fields {
		if bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return fmt.Errorf("null field %s", key)
		}
	}
	for _, key := range keys {
		if value, ok := fields[key]; !ok || bytes.Equal(bytes.TrimSpace(value), []byte("null")) {
			return fmt.Errorf("missing field %s", key)
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}
func (p *ReplicaPayload) UnmarshalJSON(data []byte) error {
	type plain ReplicaPayload
	return strictRequired(data, (*plain)(p), "title", "start_local", "end_local", "timezone", "status", "deleted")
}
func (h *ReplicaHead) UnmarshalJSON(data []byte) error {
	type plain ReplicaHead
	return strictRequired(data, (*plain)(h), "revision_id", "ancestors", "payload", "author", "changed_at")
}
func (r *ReplicaRecord) UnmarshalJSON(data []byte) error {
	type plain ReplicaRecord
	return strictRequired(data, (*plain)(r), "id", "heads")
}
func canonicalJSON(value any) []byte {
	var out bytes.Buffer
	encoder := json.NewEncoder(&out)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		panic(err)
	}
	raw := bytes.TrimSuffix(out.Bytes(), []byte{'\n'})
	result := make([]byte, 0, len(raw))
	for i := 0; i < len(raw); i++ {
		if raw[i] == '\\' && i+1 < len(raw) {
			if i+6 <= len(raw) && (string(raw[i:i+6]) == `\u2028` || string(raw[i:i+6]) == `\u2029`) {
				if raw[i+5] == '8' {
					result = append(result, []byte("\u2028")...)
				} else {
					result = append(result, []byte("\u2029")...)
				}
				i += 5
				continue
			}
			result = append(result, raw[i], raw[i+1])
			i++
			continue
		}
		result = append(result, raw[i])
	}
	return result
}
func validatePayload(p ReplicaPayload) error {
	if textLength(p.Title) < 1 || textLength(p.Title) > 120 || strings.TrimSpace(p.Title) == "" || p.Timezone != "Asia/Shanghai" || (p.Status != "planned" && p.Status != "completed") || len(p.StartLocal) != 19 || len(p.EndLocal) != 19 {
		return invalidInput()
	}
	return validateDuration(p.StartLocal, p.EndLocal)
}
func normalizeHead(h ReplicaHead) (ReplicaHead, error) {
	if !validID(h.RevisionID) || len(h.Ancestors) > maxAncestors || h.Ancestors == nil || textLength(h.Author) > 80 || h.ChangedAt < -8640000000000000 || h.ChangedAt > 8640000000000000 {
		return h, invalidInput()
	}
	if err := validatePayload(h.Payload); err != nil {
		return h, err
	}
	h.RevisionID = strings.ToLower(h.RevisionID)
	unique := map[string]bool{}
	for _, id := range h.Ancestors {
		id = strings.ToLower(id)
		if !validID(id) || id == h.RevisionID {
			return h, invalidInput()
		}
		unique[id] = true
	}
	h.Ancestors = make([]string, 0, len(unique))
	for id := range unique {
		h.Ancestors = append(h.Ancestors, id)
	}
	sort.Strings(h.Ancestors)
	return h, nil
}
func normalizeRecord(r ReplicaRecord) (ReplicaRecord, error) {
	if !validID(r.ID) || len(r.Heads) < 1 || len(r.Heads) > maxHeads {
		return r, invalidInput()
	}
	r.ID = strings.ToLower(r.ID)
	next := make([]ReplicaHead, len(r.Heads))
	for i, h := range r.Heads {
		var err error
		next[i], err = normalizeHead(h)
		if err != nil {
			return r, err
		}
	}
	r.Heads = next
	return r, nil
}
func payloadKey(p ReplicaPayload) string {
	return string(canonicalJSON([]any{p.Title, p.StartLocal, p.EndLocal, p.Timezone, p.Status, p.Deleted}))
}
func headKey(h ReplicaHead) string {
	return string(canonicalJSON([]any{h.RevisionID, h.Ancestors, payloadKey(h.Payload), h.Author, h.ChangedAt}))
}
func hasID(ids []string, id string) bool {
	for _, item := range ids {
		if item == id {
			return true
		}
	}
	return false
}

// MergeHeads joins causal frontiers; clock skew never selects a winning edit.
func MergeHeads(local, incoming []ReplicaHead) ([]ReplicaHead, error) {
	if len(local) > maxHeads || len(incoming) > maxHeads {
		return nil, invalidInput()
	}
	byID := map[string]ReplicaHead{}
	for _, set := range [][]ReplicaHead{local, incoming} {
		for _, h := range set {
			item, err := normalizeHead(h)
			if err != nil {
				return nil, err
			}
			previous, ok := byID[item.RevisionID]
			if ok && headKey(previous) != headKey(item) {
				return nil, appErr("REVISION_COLLISION", "同一版本编号的内容发生变化，已停止合并并保留本机数据。", 409)
			}
			byID[item.RevisionID] = item
		}
	}
	visited, visiting := map[string]bool{}, map[string]bool{}
	var visit func(string) error
	visit = func(id string) error {
		if visiting[id] {
			return appErr("CAUSAL_CYCLE", "版本祖先关系形成循环，已停止合并。", 409)
		}
		if visited[id] {
			return nil
		}
		visiting[id] = true
		for _, ancestor := range byID[id].Ancestors {
			if _, ok := byID[ancestor]; ok {
				if err := visit(ancestor); err != nil {
					return err
				}
			}
		}
		delete(visiting, id)
		visited[id] = true
		return nil
	}
	for id := range byID {
		if err := visit(id); err != nil {
			return nil, err
		}
	}
	superseded := map[string]bool{}
	for _, h := range byID {
		for _, ancestor := range h.Ancestors {
			superseded[ancestor] = true
			if known, ok := byID[ancestor]; ok {
				for _, older := range known.Ancestors {
					if !hasID(h.Ancestors, older) {
						return nil, appErr("INVALID_ANCESTRY", "版本缺少已知的传递祖先，已停止合并。", 409)
					}
				}
			}
		}
	}
	result := make([]ReplicaHead, 0)
	for id, h := range byID {
		if !superseded[id] {
			result = append(result, h)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].RevisionID < result[j].RevisionID })
	if len(result) > maxHeads {
		return nil, appErr("LIMIT_EXCEEDED", "并发版本过多，请先处理已有冲突；没有丢弃任何版本。", 409)
	}
	return result, nil
}
func createRevision(base []ReplicaHead, id string, payload ReplicaPayload, author string, changedAt int64) (ReplicaHead, error) {
	base, err := MergeHeads(base, nil)
	if err != nil {
		return ReplicaHead{}, err
	}
	all := map[string]bool{}
	for _, h := range base {
		all[h.RevisionID] = true
		for _, ancestor := range h.Ancestors {
			all[ancestor] = true
		}
	}
	id = strings.ToLower(id)
	if all[id] {
		return ReplicaHead{}, appErr("REVISION_COLLISION", "新修改必须使用新的版本编号。", 409)
	}
	if len(all) > maxAncestors {
		return ReplicaHead{}, appErr("LIMIT_EXCEEDED", "版本历史已达到上限，未截断或覆盖已有历史。", 409)
	}
	ancestors := make([]string, 0, len(all))
	for id := range all {
		ancestors = append(ancestors, id)
	}
	sort.Strings(ancestors)
	return normalizeHead(ReplicaHead{id, ancestors, payload, author, changedAt})
}
func distinctHeads(heads []ReplicaHead) ([]ReplicaHead, error) {
	merged, err := MergeHeads(heads, nil)
	if err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	result := make([]ReplicaHead, 0)
	for _, h := range merged {
		key := payloadKey(h.Payload)
		if !seen[key] {
			seen[key] = true
			result = append(result, h)
		}
	}
	return result, nil
}
