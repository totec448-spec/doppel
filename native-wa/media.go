package nativewa

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const maxMediaBytes int64 = 64 * 1024 * 1024

const (
	// How long captured media stays on disk, and how often that is enforced.
	// The sweep used to run once, at process start; the process then stayed up
	// for weeks, so an active chat with photos and voice notes grew the
	// app-private cache without bound until the next restart.
	mediaRetention       = 48 * time.Hour
	mediaCleanupInterval = 6 * time.Hour
)

// Upper bound for the self-reported audio/video length. The engine paces play
// receipts and the reply by it, so an inflated value from a peer would
// otherwise be a free way to stall the bot for hours.
const maxMediaDurationSeconds int64 = 10 * 60

var safeMediaID = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

type mediaRecord struct {
	ID           string `json:"id"`
	Path         string `json:"-"`
	MimeType     string `json:"mimeType"`
	OriginalName string `json:"originalName"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
}

type mediaStore struct {
	db   *sql.DB
	root string
}

func newMediaStore(db *sql.DB, root string) (*mediaStore, error) {
	clean := filepath.Clean(root)
	if clean == "." || clean == string(filepath.Separator) {
		return nil, fmt.Errorf("unsafe media directory")
	}
	if err := os.MkdirAll(clean, 0700); err != nil {
		return nil, err
	}
	store := &mediaStore{db: db, root: clean}
	if err := store.cleanup(mediaRetention); err != nil {
		newAndroidLogger("nativewa-media", "WARN").Warnf("initial cleanup failed: %v", err)
	}
	return store, nil
}

// runCleanup keeps enforcing the retention for as long as the runtime lives.
func (s *mediaStore) runCleanup(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.cleanup(mediaRetention); err != nil {
				newAndroidLogger("nativewa-media", "WARN").Warnf("periodic cleanup failed: %v", err)
			}
		}
	}
}

func (s *mediaStore) putReader(reader io.Reader, mimeType, originalName string, declared int64) (*mediaRecord, error) {
	if declared > maxMediaBytes {
		return nil, fmt.Errorf("media too large")
	}
	id, err := randomID(24)
	if err != nil {
		return nil, err
	}
	temporary := filepath.Join(s.root, id+".part")
	finalPath := filepath.Join(s.root, id+".bin")
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return nil, err
	}
	hasher := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, hasher), io.LimitReader(reader, maxMediaBytes+1))
	closeErr := file.Close()
	if copyErr != nil || closeErr != nil || written > maxMediaBytes {
		_ = os.Remove(temporary)
		if written > maxMediaBytes {
			return nil, fmt.Errorf("media too large")
		}
		if copyErr != nil {
			return nil, copyErr
		}
		return nil, closeErr
	}
	if err = os.Rename(temporary, finalPath); err != nil {
		_ = os.Remove(temporary)
		return nil, err
	}
	record := &mediaRecord{
		ID:           id,
		Path:         finalPath,
		MimeType:     normalizedMime(mimeType),
		OriginalName: safeFileName(originalName),
		Size:         written,
		SHA256:       hex.EncodeToString(hasher.Sum(nil)),
	}
	_, err = s.db.Exec(
		`INSERT INTO native_media(id, path, mime_type, original_name, size_bytes, sha256, created_at_ms)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		record.ID,
		record.Path,
		record.MimeType,
		record.OriginalName,
		record.Size,
		record.SHA256,
		time.Now().UnixMilli(),
	)
	if err != nil {
		_ = os.Remove(finalPath)
		return nil, err
	}
	return record, nil
}

func (s *mediaStore) get(id string) (*mediaRecord, error) {
	if !safeMediaID.MatchString(id) {
		return nil, os.ErrNotExist
	}
	record := &mediaRecord{ID: id}
	err := s.db.QueryRow(
		`SELECT path, mime_type, original_name, size_bytes, sha256 FROM native_media WHERE id = ?`,
		id,
	).Scan(&record.Path, &record.MimeType, &record.OriginalName, &record.Size, &record.SHA256)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(record.Path)
	if err != nil || !info.Mode().IsRegular() || info.Size() != record.Size {
		return nil, os.ErrNotExist
	}
	return record, nil
}

func (s *mediaStore) cleanup(age time.Duration) error {
	cutoff := time.Now().Add(-age).UnixMilli()
	rows, err := s.db.Query(`SELECT id, path FROM native_media WHERE created_at_ms < ?`, cutoff)
	if err != nil {
		return err
	}
	defer rows.Close()
	type expired struct{ id, path string }
	var values []expired
	for rows.Next() {
		var value expired
		if err = rows.Scan(&value.id, &value.path); err != nil {
			return err
		}
		values = append(values, value)
	}
	for _, value := range values {
		if err = os.Remove(value.path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("remove expired media: %w", err)
		}
		if _, err = s.db.Exec(`DELETE FROM native_media WHERE id = ?`, value.id); err != nil {
			return fmt.Errorf("delete expired media record: %w", err)
		}
	}
	return rows.Err()
}

func (s *mediaStore) uploadHTTP(w http.ResponseWriter, r *http.Request) {
	record, err := s.putReader(
		r.Body,
		r.Header.Get("Content-Type"),
		r.Header.Get("X-Media-Filename"),
		r.ContentLength,
	)
	if err != nil {
		http.Error(w, "media upload failed", http.StatusBadRequest)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"uploadId":  record.ID,
		"sha256":    record.SHA256,
		"sizeBytes": record.Size,
		"media":     record,
	})
}

func (s *mediaStore) downloadHTTP(w http.ResponseWriter, r *http.Request, id string) {
	record, err := s.get(id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", record.MimeType)
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": record.OriginalName}))
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeFile(w, r, record.Path)
}

func randomID(bytes int) (string, error) {
	value := make([]byte, bytes)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func normalizedMime(value string) string {
	value = strings.TrimSpace(strings.Split(value, ";")[0])
	if value == "" || len(value) > 200 || !strings.Contains(value, "/") {
		return "application/octet-stream"
	}
	return value
}

func safeFileName(value string) string {
	value = filepath.Base(strings.TrimSpace(value))
	value = regexp.MustCompile(`[^A-Za-z0-9._-]`).ReplaceAllString(value, "_")
	if len(value) > 120 {
		value = value[:120]
	}
	if value == "" || value == "." {
		return "media.bin"
	}
	return value
}
