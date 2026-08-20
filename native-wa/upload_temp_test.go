package nativewa

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestCreatePrivateUploadTempUsesRequestedDirectory(t *testing.T) {
	root := t.TempDir()
	file, err := createPrivateUploadTemp(root)
	if err != nil {
		t.Fatalf("create private upload temp: %v", err)
	}
	name := file.Name()
	defer os.Remove(name)
	defer file.Close()

	if filepath.Dir(name) != root {
		t.Fatalf("temp file escaped private root: got %q want %q", filepath.Dir(name), root)
	}
	if _, err = file.Write([]byte("encrypted upload staging")); err != nil {
		t.Fatalf("write temp file: %v", err)
	}
	info, err := file.Stat()
	if err != nil {
		t.Fatalf("stat temp file: %v", err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		t.Fatalf("temp file is accessible outside owner: mode=%#o", info.Mode().Perm())
	}
}

func TestCreatePrivateUploadTempRejectsMissingDirectory(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "missing")
	if file, err := createPrivateUploadTemp(missing); err == nil {
		_ = file.Close()
		_ = os.Remove(file.Name())
		t.Fatal("expected missing private directory to fail")
	}
}
