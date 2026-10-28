package app

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

func modelCryptoError() error {
	return appErr("MODEL_ENCRYPTION_UNAVAILABLE", "无法安全读取模型加密密钥，请恢复数据库旁的 model-encryption.key，或检查持久磁盘权限。", 503)
}
func explicitModelKey() ([]byte, error) {
	v := strings.TrimSpace(os.Getenv("MODEL_SETTINGS_ENCRYPTION_KEY"))
	if v == "" {
		return nil, nil
	}
	b, e := base64.StdEncoding.DecodeString(v)
	if e != nil || len(b) != 32 || base64.StdEncoding.EncodeToString(b) != v {
		return nil, modelCryptoError()
	}
	return b, nil
}
func readModelKey(path string) ([]byte, error) {
	info, e := os.Lstat(path)
	if e != nil {
		return nil, e
	}
	if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return nil, modelCryptoError()
	}
	b, e := os.ReadFile(path)
	if e != nil || len(b) != 32 {
		return nil, modelCryptoError()
	}
	return b, nil
}

// Preserve the original Node key file and authenticated AES-GCM envelope.
func (s *Store) encryptionKey(allowCreate bool) ([]byte, error) {
	if key, e := explicitModelKey(); e != nil || key != nil {
		return key, e
	}
	path := filepath.Join(filepath.Dir(s.Path), "model-encryption.key")
	if key, e := readModelKey(path); e == nil {
		return key, nil
	} else if !errors.Is(e, os.ErrNotExist) || !allowCreate {
		return nil, modelCryptoError()
	}
	// A missing key is recoverable only for a database with no encrypted values.
	// Replacing a lost key would strand the remaining model and peer secrets.
	var existing int
	if e := s.DB.QueryRow("SELECT (SELECT count(*) FROM model_settings WHERE coalesce(api_key_ciphertext,'')<>'') + (SELECT count(*) FROM peer_connections WHERE credential<>'')").Scan(&existing); e != nil || existing != 0 {
		return nil, modelCryptoError()
	}
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return nil, modelCryptoError()
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".model-key-*.tmp")
	if e != nil {
		return nil, modelCryptoError()
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	key := make([]byte, 32)
	if _, e = rand.Read(key); e == nil {
		_, e = f.Write(key)
	}
	if e == nil {
		e = f.Sync()
	}
	ce := f.Close()
	if e != nil || ce != nil {
		return nil, modelCryptoError()
	}
	if e = protectModelKey(tmp); e != nil {
		return nil, modelCryptoError()
	}
	// Publish after setting permissions; never replace a concurrently created key.
	if e = os.Link(tmp, path); e != nil && !errors.Is(e, os.ErrExist) {
		return nil, modelCryptoError()
	}
	key, e = readModelKey(path)
	if e != nil {
		return nil, modelCryptoError()
	}
	return key, nil
}
func (s *Store) modelEncryptionAvailable() bool {
	if key, e := explicitModelKey(); e != nil {
		return false
	} else if key != nil {
		return true
	}
	_, e := readModelKey(filepath.Join(filepath.Dir(s.Path), "model-encryption.key"))
	return e == nil || errors.Is(e, os.ErrNotExist)
}
func encryptSecret(value, space string, key []byte) (string, error) {
	block, e := aes.NewCipher(key)
	if e != nil || len(key) != 32 {
		return "", modelCryptoError()
	}
	g, e := cipher.NewGCM(block)
	if e != nil {
		return "", modelCryptoError()
	}
	nonce := make([]byte, g.NonceSize())
	if _, e = rand.Read(nonce); e != nil {
		return "", modelCryptoError()
	}
	enc := g.Seal(nil, nonce, []byte(value), []byte("shunshoupai:model-key:"+space))
	tag := enc[len(enc)-g.Overhead():]
	body := enc[:len(enc)-g.Overhead()]
	return strings.Join([]string{"v1", base64.RawURLEncoding.EncodeToString(nonce), base64.RawURLEncoding.EncodeToString(tag), base64.RawURLEncoding.EncodeToString(body)}, "."), nil
}
func decryptSecret(value, space string, key []byte) (string, error) {
	p := strings.Split(value, ".")
	if len(p) != 4 || p[0] != "v1" || len(key) != 32 {
		return "", modelCryptoError()
	}
	d := make([][]byte, 3)
	for i := range d {
		b, e := base64.RawURLEncoding.Strict().DecodeString(p[i+1])
		if e != nil || base64.RawURLEncoding.EncodeToString(b) != p[i+1] {
			return "", modelCryptoError()
		}
		d[i] = b
	}
	if len(d[0]) != 12 || len(d[1]) != 16 || len(d[2]) < 1 || len(d[2]) > 4096 {
		return "", modelCryptoError()
	}
	block, e := aes.NewCipher(key)
	if e != nil {
		return "", modelCryptoError()
	}
	g, e := cipher.NewGCM(block)
	if e != nil {
		return "", modelCryptoError()
	}
	b, e := g.Open(nil, d[0], append(d[2], d[1]...), []byte("shunshoupai:model-key:"+space))
	if e != nil {
		return "", modelCryptoError()
	}
	return string(b), nil
}
