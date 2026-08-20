package nativewa

import (
	"context"
	"errors"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"reflect"
	"strings"
	"testing"

	"go.mau.fi/whatsmeow"
)

func TestValidBridgeTokenMatchesAndroidContract(t *testing.T) {
	validHex := strings.Repeat("ab", 32)
	validBase64URL := strings.Repeat("A", 43)
	for _, token := range []string{validHex, validBase64URL} {
		if !validBridgeToken(token) {
			t.Fatalf("expected valid 256-bit token encoding: %q", token)
		}
	}
	for _, token := range []string{
		strings.Repeat("!", 64),
		strings.Repeat("A", 42),
		strings.Repeat("A", 43) + "=",
	} {
		if validBridgeToken(token) {
			t.Fatalf("accepted invalid bridge token: %q", token)
		}
	}
}

func TestStopCancelsAndJoinsRuntimeWorkers(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	runtime := &Runtime{
		ctx:    ctx,
		cancel: cancel,
	}
	finished := make(chan struct{})
	if !runtime.launch(func() {
		<-ctx.Done()
		close(finished)
	}) {
		t.Fatal("expected worker to launch")
	}

	runtime.Stop()

	select {
	case <-finished:
	default:
		t.Fatal("Stop returned before its worker finished")
	}
	if runtime.launch(func() {}) {
		t.Fatal("stopped runtime accepted another worker")
	}
}

func TestServerCapabilitiesMatchAndroidRuntimeContract(t *testing.T) {
	required := []string{
		"protocol.v1",
		"resume.sequence",
		"replay.ready",
		"journal.durable",
		"idempotency.durable",
		"media.http-stream",
		"event.connection",
		"event.incoming",
		"event.delivery",
		"event.safety",
		"action.pair",
		"action.reconnect",
		"action.link_sleep",
		"action.link_wake",
		"action.logout",
		"action.safety_refresh",
		"action.send_text",
		"action.send_reply",
		"action.send_media",
		"action.send_reaction",
		"action.mark_read",
		"action.mark_played",
		"action.presence",
		"action.set_ingress_policy",
		"action.edit_message",
		"action.delete_message",
		"action.set_profile_picture",
		"action.set_status_message",
		"action.set_push_name",
		"action.block",
		"action.unblock",
	}
	if !reflect.DeepEqual(serverCapabilities, required) {
		t.Fatalf("native bridge capabilities drifted from Android contract:\n got: %v\nwant: %v", serverCapabilities, required)
	}
}

// The advertised action capabilities are a promise the Android runtime holds the
// bridge to, so they have to describe the dispatcher rather than a hand-kept
// list beside it. Reading the switch itself is what makes the two impossible to
// drift apart: delete_message was implemented and used while never being
// advertised, and nothing failed until a client checked for it.
func TestAdvertisedActionsCoverTheDispatcher(t *testing.T) {
	source, err := parser.ParseFile(token.NewFileSet(), "actions.go", nil, 0)
	if err != nil {
		t.Fatalf("parse actions.go: %v", err)
	}
	implemented := map[string]bool{}
	for _, declaration := range source.Decls {
		function, ok := declaration.(*ast.FuncDecl)
		if !ok || function.Name.Name != "executeAction" {
			continue
		}
		ast.Inspect(function, func(node ast.Node) bool {
			clause, ok := node.(*ast.CaseClause)
			if !ok {
				return true
			}
			for _, expression := range clause.List {
				literal, ok := expression.(*ast.BasicLit)
				if ok && literal.Kind == token.STRING {
					implemented[strings.Trim(literal.Value, `"`)] = true
				}
			}
			return true
		})
	}
	if len(implemented) == 0 {
		t.Fatal("no action cases found in executeAction")
	}
	advertised := map[string]bool{}
	for _, capability := range serverCapabilities {
		if name, found := strings.CutPrefix(capability, "action."); found {
			advertised[name] = true
		}
	}
	for action := range implemented {
		if !advertised[action] {
			t.Errorf("executeAction handles %q but the bridge does not advertise action.%s", action, action)
		}
	}
	for action := range advertised {
		if !implemented[action] {
			t.Errorf("bridge advertises action.%s but executeAction does not handle it", action)
		}
	}
}

// Every action taken out of the serialized lane must also be exempt from the
// idempotency journal: the journal's read-execute-store cycle is what the
// action mutex protects.
func TestUnserializedActionsAreExemptFromTheJournal(t *testing.T) {
	for action := range unserializedActions {
		if !transientActions[action] {
			t.Errorf("%q runs unserialized but is journaled", action)
		}
	}
	for _, owning := range []string{"pair", "reconnect"} {
		if unserializedActions[owning] {
			t.Errorf("%q tears the socket down and must stay serialized", owning)
		}
	}
}

func TestPublicErrorDoesNotExposeRawDiagnostic(t *testing.T) {
	result := publicError(errors.New("pairing failed\r\nserver rejected client"))
	if result["message"] != "On-device WhatsApp operation failed" {
		t.Fatalf("unexpected public diagnostic: %q", result["message"])
	}
}

func TestWhatsAppRestrictionIsClassifiedWithoutRawPayload(t *testing.T) {
	err := fmt.Errorf("%w %d", whatsmeow.ErrServerReturnedError, 463)
	code, message := classifyActionError(err)
	if code != "timelock_463" || message != "WhatsApp restricted outbound messaging" {
		t.Fatalf("unexpected classification: code=%q message=%q", code, message)
	}
}

func TestWhatsAppStatusErrorParsesCodeBeforeReasonText(t *testing.T) {
	err := fmt.Errorf("%w: info query returned status 400: bad-request", whatsmeow.ErrServerReturnedError)
	if code := whatsmeowServerErrorCode(err); code != 400 {
		t.Fatalf("status code = %d, want 400", code)
	}
}

func TestInvalidBlockTargetIsPermanentAndPrivacySafe(t *testing.T) {
	code, message := classifyActionError(fmt.Errorf("%w: private diagnostic", errInvalidBlockTarget))
	if code != "invalid_block_target" || message != "WhatsApp could not resolve the contact block identity" {
		t.Fatalf("unexpected classification: code=%q message=%q", code, message)
	}
}

func TestPairClientDisplayNameUsesRequiredFormat(t *testing.T) {
	if pairClientDisplayName != "Chrome (Android)" {
		t.Fatalf("pair client display must stay in Browser (OS) format: %q", pairClientDisplayName)
	}
}

func TestDevicePropertiesUseProductName(t *testing.T) {
	if serverName != "Doppel" {
		t.Fatalf("device product name = %q", serverName)
	}
}
