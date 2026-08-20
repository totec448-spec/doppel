package nativewa

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"testing"
)

func writeTestImage(t *testing.T, name string, width, height int, encode func(*os.File, image.Image) error) string {
	t.Helper()
	source := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			source.Set(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 120, A: 255})
		}
	}
	path := filepath.Join(t.TempDir(), name)
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if err = encode(file, source); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestSquareJPEGProducesTheSizeWhatsAppExpects(t *testing.T) {
	// A wide picture: WhatsApp wants a square, and a stretched avatar is exactly
	// the kind of detail that reads as "not sent from a phone".
	path := writeTestImage(t, "wide.jpg", 1200, 600, func(f *os.File, img image.Image) error {
		return jpeg.Encode(f, img, nil)
	})

	encoded, err := squareJPEG(path)
	if err != nil {
		t.Fatal(err)
	}
	decoded, format, err := image.Decode(bytes.NewReader(encoded))
	if err != nil {
		t.Fatal(err)
	}
	if format != "jpeg" {
		t.Fatalf("the server rejects anything but JPEG, got %q", format)
	}
	bounds := decoded.Bounds()
	if bounds.Dx() != profilePictureSize || bounds.Dy() != profilePictureSize {
		t.Fatalf("expected %dx%d, got %dx%d", profilePictureSize, profilePictureSize, bounds.Dx(), bounds.Dy())
	}
}

func TestSquareJPEGReencodesAPNG(t *testing.T) {
	path := writeTestImage(t, "avatar.png", 400, 400, func(f *os.File, img image.Image) error {
		return png.Encode(f, img)
	})

	encoded, err := squareJPEG(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, format, err := image.Decode(bytes.NewReader(encoded)); err != nil || format != "jpeg" {
		t.Fatalf("a PNG must come back out as JPEG (format=%q err=%v)", format, err)
	}
}

func TestSquareJPEGRejectsSomethingThatIsNotAPicture(t *testing.T) {
	path := filepath.Join(t.TempDir(), "not-an-image.bin")
	if err := os.WriteFile(path, []byte("definitely not a picture"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := squareJPEG(path); err == nil {
		t.Fatal("expected a decode failure instead of uploading junk as an avatar")
	}
}
