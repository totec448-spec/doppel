package nativewa

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"os"
	"strings"
	"time"
	"unicode/utf8"

	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/types"
	"golang.org/x/image/draw"
)

// WhatsApp's own clients upload a square JPEG here. 640px is what the app
// produces for a profile picture; the server rejects anything it cannot read
// with ErrInvalidImageFormat, so the asset is re-encoded rather than trusted.
const profilePictureSize = 640

// setProfilePicture points the account's own avatar at a previously uploaded
// media record. whatsmeow has no dedicated call for this: SetGroupPhoto with an
// empty JID leaves the IQ target empty, which is exactly the stanza the app
// sends for its own picture.
func (r *Runtime) setProfilePicture(payload map[string]any) (any, error) {
	record, err := r.media.get(requiredString(payload, "mediaId"))
	if err != nil {
		return nil, fmt.Errorf("media upload not found")
	}
	avatar, err := squareJPEG(record.Path)
	if err != nil {
		return nil, err
	}
	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	defer cancel()
	pictureID, err := r.wa().SetGroupPhoto(ctx, types.EmptyJID, avatar)
	if err != nil {
		return nil, err
	}
	return map[string]any{"pictureId": pictureID}, nil
}

// The "info" line under the name. WhatsApp's own limit is well above this; the
// cap only stops a malformed payload from being pushed to the server.
const maxStatusMessageRunes = 139
const maxPushNameRunes = 25

// setPushName follows a deliberate persona change. It is never polled or refreshed: one settings
// transition produces at most one app-state patch, matching how a person changes the account name.
func (r *Runtime) setPushName(payload map[string]any) (any, error) {
	name := strings.TrimSpace(requiredString(payload, "name"))
	if name == "" {
		return nil, fmt.Errorf("push name is empty")
	}
	if utf8.RuneCountInString(name) > maxPushNameRunes {
		return nil, fmt.Errorf("push name is too long")
	}
	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	defer cancel()
	if err := r.wa().SendAppState(ctx, appstate.BuildSettingPushName(name)); err != nil {
		return nil, err
	}
	return map[string]any{"name": name}, nil
}

// setStatusMessage rewrites the account's "info" text — the second half of a
// profile that never changes on a bot and always changes on a person.
func (r *Runtime) setStatusMessage(payload map[string]any) (any, error) {
	text := strings.TrimSpace(requiredString(payload, "text"))
	if text == "" {
		return nil, fmt.Errorf("status message is empty")
	}
	if utf8.RuneCountInString(text) > maxStatusMessageRunes {
		return nil, fmt.Errorf("status message is too long")
	}
	ctx, cancel := r.contextWithTimeout(30 * time.Second)
	defer cancel()
	// Text is a pointer because the field is what distinguishes "set it to this" from "leave it
	// alone"; a zero Duration is what keeps the About permanent rather than ephemeral, and no
	// emoji is what keeps it a plain info line.
	if err := r.wa().SetStatusMessage(ctx, types.SetStatusInput{Text: &text}); err != nil {
		return nil, err
	}
	return map[string]any{"applied": true}, nil
}

// squareJPEG center-crops the picture to a square and re-encodes it as JPEG.
// The seeded assets are already square, so this is normally a straight re-encode
// — it exists so a picture that came from somewhere else cannot get the upload
// rejected for a format the server dislikes.
func squareJPEG(path string) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	source, _, err := image.Decode(file)
	_ = file.Close()
	if err != nil {
		return nil, fmt.Errorf("profile picture could not be decoded: %w", err)
	}
	bounds := source.Bounds()
	side := bounds.Dx()
	if bounds.Dy() < side {
		side = bounds.Dy()
	}
	if side <= 0 {
		return nil, fmt.Errorf("profile picture is empty")
	}
	crop := image.Rect(0, 0, side, side).Add(image.Pt(
		bounds.Min.X+(bounds.Dx()-side)/2,
		bounds.Min.Y+(bounds.Dy()-side)/2,
	))
	target := image.NewRGBA(image.Rect(0, 0, profilePictureSize, profilePictureSize))
	draw.CatmullRom.Scale(target, target.Bounds(), source, crop, draw.Src, nil)
	var encoded bytes.Buffer
	if err = jpeg.Encode(&encoded, target, &jpeg.Options{Quality: 88}); err != nil {
		return nil, err
	}
	return encoded.Bytes(), nil
}
