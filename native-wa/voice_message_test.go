package nativewa

import (
	"bytes"
	"encoding/base64"
	"testing"
)

func TestVoiceDurationSecondsBoundsInvalidAndRounds(t *testing.T) {
	if got := voiceDurationSeconds(nil); got != 1 {
		t.Fatalf("invalid duration = %d, want 1", got)
	}
	if got := voiceDurationSeconds(float64(3.6)); got != 4 {
		t.Fatalf("rounded duration = %d, want 4", got)
	}
	if got := voiceDurationSeconds(float64(100000)); got != 86400 {
		t.Fatalf("bounded duration = %d, want 86400", got)
	}
}

func TestFallbackVoiceWaveformMatchesWhatsAppShape(t *testing.T) {
	waveform := fallbackVoiceWaveform("d1e8f0")
	if len(waveform) != voiceWaveformSamples {
		t.Fatalf("waveform length = %d, want %d", len(waveform), voiceWaveformSamples)
	}
	for index, sample := range waveform {
		if sample > 100 {
			t.Fatalf("waveform[%d] = %d, above the 0..100 scale", index, sample)
		}
	}
}

// One constant waveform under every voice note is the fingerprint this seeding
// exists to remove; a retry of the same upload still has to reproduce the shape
// already on the wire.
func TestFallbackVoiceWaveformIsStablePerUploadAndDiffersAcrossUploads(t *testing.T) {
	first := fallbackVoiceWaveform("a1b2c3")
	if !bytes.Equal(first, fallbackVoiceWaveform("a1b2c3")) {
		t.Fatal("the same upload produced two different waveforms")
	}
	if bytes.Equal(first, fallbackVoiceWaveform("c3b2a1")) {
		t.Fatal("two different uploads produced the same waveform")
	}
}

func TestVoiceWaveformPrefersTheMeasuredEnvelope(t *testing.T) {
	measured := make([]byte, voiceWaveformSamples)
	for index := range measured {
		measured[index] = byte(index)
	}
	encoded := base64.StdEncoding.EncodeToString(measured)
	if got := voiceWaveform(encoded, "seed"); !bytes.Equal(got, measured) {
		t.Fatalf("waveform = %v, want the measured envelope", got)
	}
}

func TestVoiceWaveformRejectsUnusableEnvelopes(t *testing.T) {
	seeded := fallbackVoiceWaveform("seed")
	for name, value := range map[string]any{
		"missing":    nil,
		"empty":      "",
		"not base64": "!!!!",
		"too short":  base64.StdEncoding.EncodeToString(make([]byte, 32)),
		"too long":   base64.StdEncoding.EncodeToString(make([]byte, 128)),
	} {
		if got := voiceWaveform(value, "seed"); !bytes.Equal(got, seeded) {
			t.Errorf("%s envelope was not replaced by the seeded fallback", name)
		}
	}
	clamped := make([]byte, voiceWaveformSamples)
	for index := range clamped {
		clamped[index] = 250
	}
	for index, sample := range voiceWaveform(base64.StdEncoding.EncodeToString(clamped), "seed") {
		if sample != 100 {
			t.Fatalf("clamped waveform[%d] = %d, want 100", index, sample)
		}
	}
}
