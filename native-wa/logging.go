package nativewa

import (
	"fmt"
	"log"
	"regexp"

	waLog "go.mau.fi/whatsmeow/util/log"
)

var logLevels = map[string]int{"DEBUG": 0, "INFO": 1, "WARN": 2, "ERROR": 3}

var logRedactions = []struct {
	pattern     *regexp.Regexp
	replacement string
}{
	{
		regexp.MustCompile(`(?i)\bBearer[ \t]+[A-Za-z0-9._~+/=-]{12,}`),
		"Bearer <redacted>",
	},
	{
		regexp.MustCompile(`(?i)\bsk-(?:or-v1-|proj-)?[A-Za-z0-9_-]{12,}`),
		"<redacted-secret>",
	},
	{
		regexp.MustCompile(`(?i)[A-Za-z0-9._:+-]{1,160}@(s\.whatsapp\.net|lid|g\.us|broadcast|newsletter|call)`),
		"<redacted-jid>",
	},
	{
		regexp.MustCompile(`(?i)(pair(?:ing)?(?:[ _-]?code)?[=: ]+)[A-Z0-9-]{8,}`),
		"${1}<redacted>",
	},
	{
		regexp.MustCompile(`(?i)[A-Z]:\\(?:Users|Documents and Settings)\\[^\r\n\t\"'<> ]+`),
		"<redacted-path>",
	},
	{
		regexp.MustCompile(`(?i)/(?:home|Users)/[^\r\n\t\"'<> ]+`),
		"<redacted-path>",
	},
	{
		regexp.MustCompile(`\+?[0-9][0-9 ()-]{8,}[0-9]`),
		"<redacted-number>",
	},
}

// sanitizeLogMessage keeps warning/error diagnostics useful without copying credentials, contact
// identifiers, pairing codes, or workstation paths into logcat. It is intentionally best-effort:
// callers must still avoid logging message bodies or opaque protocol payloads in the first place.
func sanitizeLogMessage(message string) string {
	for _, redaction := range logRedactions {
		message = redaction.pattern.ReplaceAllString(message, redaction.replacement)
	}
	return message
}

// androidLogger routes whatsmeow diagnostics into logcat.
//
// waLog.Stdout prints with fmt.Printf, and Android discards a process's stdout,
// so every warning and error whatsmeow produced was invisible — including the
// ones that would explain why a link died. gomobile redirects the standard log
// package to logcat (tag "GoLog"), which is the one channel this package can
// reach without pulling JNI into it. Read it with `adb logcat -s GoLog`.
type androidLogger struct {
	module string
	min    int
}

func newAndroidLogger(module, minLevel string) waLog.Logger {
	return androidLogger{module: module, min: logLevels[minLevel]}
}

func (l androidLogger) outputf(level, msg string, args ...any) {
	if logLevels[level] < l.min {
		return
	}
	line := sanitizeLogMessage(
		fmt.Sprintf("[%s %s] %s", l.module, level, fmt.Sprintf(msg, args...)),
	)
	log.Print(line)
}

func (l androidLogger) Errorf(msg string, args ...any) { l.outputf("ERROR", msg, args...) }

func (l androidLogger) Warnf(msg string, args ...any) { l.outputf("WARN", msg, args...) }

func (l androidLogger) Infof(msg string, args ...any) { l.outputf("INFO", msg, args...) }

func (l androidLogger) Debugf(msg string, args ...any) { l.outputf("DEBUG", msg, args...) }

func (l androidLogger) Sub(module string) waLog.Logger {
	return androidLogger{module: l.module + "/" + module, min: l.min}
}
