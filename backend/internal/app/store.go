package app

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

type Object = map[string]any
type DBTX interface {
	Exec(string, ...any) (sql.Result, error)
	Query(string, ...any) (*sql.Rows, error)
	QueryRow(string, ...any) *sql.Row
}
type Store struct {
	DB   *sql.DB
	Path string
	mu   sync.Mutex
}

//go:embed schema.sql
var schema string

func OpenStore(path string) (*Store, error) {
	if path != ":memory:" {
		if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
			return nil, err
		}
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	s := &Store{DB: db, Path: path}
	if _, err = db.Exec(schema); err == nil {
		err = initDevices(db)
	}
	if err == nil {
		err = initReplica(db)
	}
	if err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}
func (s *Store) Close() error { return s.DB.Close() }
func (s *Store) Transaction(fn func(*sql.Tx) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.DB.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}
func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}
func newID() string {
	b := randomBytes(16)
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}
func token() string { return base64.RawURLEncoding.EncodeToString(randomBytes(32)) }
func hash(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
func nowISO() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000Z") }

var shanghai = time.FixedZone("Asia/Shanghai", 8*60*60)
var localPattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$`)

func parseLocal(value string) (time.Time, error) {
	if !localPattern.MatchString(value) {
		return time.Time{}, appErr("VALIDATION_ERROR", "日期或时间无效。", 400)
	}
	layout := "2006-01-02T15:04:05"
	if len(value) == 16 {
		layout = "2006-01-02T15:04"
	}
	t, err := time.ParseInLocation(layout, value, shanghai)
	if err != nil || t.Year() < 2000 || t.Year() > 2100 {
		return time.Time{}, appErr("VALIDATION_ERROR", "日期或时间无效。", 400)
	}
	return t, nil
}
func isoToLocal(value string) (string, error) {
	t, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return "", err
	}
	return t.In(shanghai).Format("2006-01-02T15:04:05"), nil
}
