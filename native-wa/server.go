// Package nativewa embeds a low-overhead WhatsApp linked-device transport in
// the Android process. gomobile compiles this package into the APK's AAR.
package nativewa

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	waStore "go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types/events"
)

const (
	protocolVersion = 1
	serverName      = "Doppel"
	// PairPhone is validated by WhatsApp as Browser (OS). Free-form names receive HTTP 400,
	// so the product name belongs in device properties while this wire value remains compatible.
	pairClientDisplayName = "Chrome (Android)"
)

// Keep this wire contract aligned with BridgeSequenceGuard.REQUIRED_CAPABILITIES.
// The Android runtime refuses a bridge that cannot provide its durable replay,
// event, and action guarantees.
var serverCapabilities = []string{
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

// Runtime is the gomobile-visible lifetime handle. The HTTP/WebSocket server
// only binds to Android loopback and still requires a 256-bit bearer token.
type Runtime struct {
	mu sync.RWMutex

	token     string
	db        *sql.DB
	container *sqlstore.Container
	journal   *eventJournal
	media     *mediaStore
	// waClient is replaced on every relink. Read it through wa(); the direct field is
	// only for code that already holds mu.
	waClient *whatsmeow.Client
	device   *waStore.Device

	server      *http.Server
	listener    net.Listener
	sessions    map[*websocket.Conn]*wsSession
	ctx         context.Context
	cancel      context.CancelFunc
	stopped     bool
	workers     sync.WaitGroup
	actionSlots chan struct{}

	ingress ingressPolicy
	actions sync.Mutex

	// Safety refresh combines several WhatsApp IQs. Connection recovery and the operator opening
	// Safety can happen together, so collapse them into one short-lived result instead of asking
	// the same account questions twice in parallel.
	safetyRefreshMu sync.Mutex
	safetyCachedAt  time.Time
	// lastLimitProbe throttles the probe a rejected send triggers, independently of
	// the read cache that serves ordinary refreshes.
	lastLimitProbe time.Time

	// Local mirror of WhatsApp's blocklist, kept current from the change pushes
	// WhatsApp sends anyway. Guarded by mu. See rememberBlocklist.
	blocklistMirror     map[string]bool
	blocklistDHash      string
	blocklistMirroredAt time.Time
	safetyCached        map[string]any

	// publishes serializes the resume handshake against event publication.
	// Without it, a frame appended between reading the journal bounds and
	// marking the session ready reached neither the replay nor the live
	// broadcast, and the client saw a sequence gap it could not close.
	publishes sync.Mutex

	connectionEpoch   uint64
	recentDisconnects []time.Time
	linkDown          bool

	// Call IDs already answered, so one call cannot be declined twice when
	// WhatsApp repeats the offer or emits both an offer and an offer notice.
	handledCalls map[string]time.Time

	// whatsmeow can replay the same stanza around reconnects. Claim it before media download so a
	// duplicate event cannot repeat a multi-megabyte WhatsApp request merely to be deduped later by
	// Android's processed-events table.
	incomingSeen map[string]time.Time

	// Inbound messages are handed to workers instead of being normalized on
	// whatsmeow's node handler goroutine. That loop processes one node at a
	// time and waits on the handler, so the synchronous media download inside
	// normalization (up to two minutes) used to stall every following node —
	// other chats' messages, receipts, retries — behind one slow attachment.
	//
	// One lane per shard, chosen by chat: order inside a conversation is preserved,
	// while a slow attachment in one chat no longer holds up another. See
	// incomingShardFor.
	incoming []chan *events.Message

	// Closed by Stop, so an enqueue waiting on a full queue cannot outlive the
	// runtime and pin whatsmeow's handler goroutine during shutdown.
	shutdown <-chan struct{}

	// Per-chat disappearing-message timers, so outgoing messages expire on the
	// same schedule as the contact's. Guarded by mu, persisted by the journal.
	ephemeral map[string]ephemeralSetting

	// Group subjects are loaded in one joined-groups request per native runtime, then maintained
	// from normal metadata events. Incoming messages can therefore carry a useful local title
	// without a profile/group query for every chat row or every message.
	groupNames         map[string]string
	groupCatalogLoaded bool
}

// incomingQueueDepth is per shard, and generous because a queue only ever backs
// up behind a slow download. Dropping an inbound message is not an option: a full
// queue applies backpressure to whatsmeow instead, which is the old behaviour and
// therefore never worse than before.
const incomingQueueDepth = 64

// incomingShards is how many chats can be normalized in parallel. Four is enough
// to stop one slow attachment from gating the rest without ever looking like a
// client that downloads everything at once.
const incomingShards = 4

func newIncomingShards() []chan *events.Message {
	lanes := make([]chan *events.Message, incomingShards)
	for index := range lanes {
		lanes[index] = make(chan *events.Message, incomingQueueDepth)
	}
	return lanes
}

const maxConcurrentActions = 24
const maxRememberedIncoming = 8192
const incomingMemory = 24 * time.Hour

type wsSession struct {
	conn     *websocket.Conn
	writeMu  sync.Mutex
	clientID string
	// ready is set once the resume handshake finished, on the socket-read
	// goroutine, and read by publish on whichever goroutine produced the event.
	// A plain bool is a data race there, and the practical symptom is the worst
	// kind: a freshly ready session silently misses live frames until some
	// unrelated write happens to make the store visible.
	ready atomic.Bool
}

func (s *wsSession) setReady(value bool) { s.ready.Store(value) }

func (s *wsSession) isReady() bool { return s.ready.Load() }

type clientFrame struct {
	Version     int             `json:"v"`
	Type        string          `json:"type"`
	ClientID    string          `json:"clientId"`
	ResumeAfter int64           `json:"resumeAfter"`
	Sequence    int64           `json:"sequence"`
	ID          string          `json:"id"`
	Action      string          `json:"action"`
	Payload     json.RawMessage `json:"payload"`
}

type ingressPolicy struct {
	Configured bool
	AllowAll   bool
	Allowed    map[string]bool
	MediaKinds map[string]bool
}

// Start launches the native bridge on 127.0.0.1:port. dbPath and mediaDir must
// point into the app-private files directory.
func Start(dbPath, mediaDir, token string, port int) (*Runtime, error) {
	if port < 1024 || port > 65535 {
		return nil, fmt.Errorf("invalid loopback port")
	}
	if !validBridgeToken(token) {
		return nil, fmt.Errorf("bridge token must encode at least 256 random bits")
	}
	if err := os.MkdirAll(filepath.Dir(dbPath), 0700); err != nil {
		return nil, fmt.Errorf("create native database directory: %w", err)
	}
	db, err := sql.Open("sqlite3", "file:"+filepath.ToSlash(dbPath)+"?_foreign_keys=on&_busy_timeout=5000&_journal_mode=WAL")
	if err != nil {
		return nil, fmt.Errorf("open native database: %w", err)
	}
	db.SetMaxOpenConns(4)
	journal, err := openJournal(db)
	if err != nil {
		db.Close()
		return nil, err
	}
	media, err := newMediaStore(db, mediaDir)
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("open native media store: %w", err)
	}

	logger := newAndroidLogger("nativewa", "WARN")
	container := sqlstore.NewWithDB(db, "sqlite3", logger)
	ctx, cancel := context.WithCancel(context.Background())
	if err = container.Upgrade(ctx); err != nil {
		cancel()
		db.Close()
		return nil, fmt.Errorf("upgrade WhatsApp store: %w", err)
	}
	waStore.SetOSInfo(serverName, [3]uint32{1, 0, 0})
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		cancel()
		db.Close()
		return nil, fmt.Errorf("load WhatsApp device: %w", err)
	}
	if device == nil {
		device = container.NewDevice()
	}

	runtime := &Runtime{
		token:        token,
		db:           db,
		container:    container,
		journal:      journal,
		media:        media,
		device:       device,
		sessions:     make(map[*websocket.Conn]*wsSession),
		ctx:          ctx,
		cancel:       cancel,
		incoming:     newIncomingShards(),
		incomingSeen: make(map[string]time.Time),
		shutdown:     ctx.Done(),
		actionSlots:  make(chan struct{}, maxConcurrentActions),
		groupNames:   make(map[string]string),
		ingress: ingressPolicy{
			Allowed:    make(map[string]bool),
			MediaKinds: make(map[string]bool),
		},
	}
	// A restart must not send the next reply into a disappearing chat unstamped,
	// so the timers are restored before the socket comes up. A read failure is
	// not fatal: the timers are relearned from the next message in each chat.
	if stored, loadErr := journal.loadEphemeral(); loadErr == nil {
		runtime.ephemeral = stored
	} else {
		runtime.ephemeral = make(map[string]ephemeralSetting)
	}
	if err = runtime.createWhatsAppClient(); err != nil {
		runtime.Stop()
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/socket", runtime.handleSocket)
	mux.HandleFunc("/v1/media", runtime.handleMediaUpload)
	mux.HandleFunc("/v1/media/", runtime.handleMediaDownload)
	mux.HandleFunc("/v1/health", runtime.handleHealth)
	runtime.server = &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    32 * 1024,
	}
	runtime.listener, err = net.Listen("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		runtime.Stop()
		return nil, fmt.Errorf("bind Android loopback bridge: %w", err)
	}
	runtime.launch(func() {
		_ = runtime.server.Serve(runtime.listener)
	})
	runtime.launch(func() { media.runCleanup(ctx, mediaCleanupInterval) })
	for shard := range runtime.incoming {
		lane := shard
		runtime.launch(func() { runtime.runIncomingLoop(ctx, lane) })
	}
	if device.ID != nil {
		runtime.launch(runtime.connectWhatsApp)
	}
	return runtime, nil
}

// Stop closes the linked-device socket, loopback server, and local databases.
func (r *Runtime) Stop() {
	if r == nil {
		return
	}
	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		return
	}
	r.stopped = true
	sessions := make([]*websocket.Conn, 0, len(r.sessions))
	for conn := range r.sessions {
		sessions = append(sessions, conn)
	}
	r.sessions = make(map[*websocket.Conn]*wsSession)
	r.mu.Unlock()

	r.cancel()
	if client := r.wa(); client != nil {
		client.Disconnect()
	}
	for _, conn := range sessions {
		_ = conn.Close(websocket.StatusNormalClosure, "native bridge stopped")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	if r.server != nil {
		_ = r.server.Shutdown(ctx)
	}
	cancel()
	r.workers.Wait()
	if r.db != nil {
		_ = r.db.Close()
	}
}

// launch gives every runtime-owned goroutine one shutdown owner. Holding mu
// across the stopped check and WaitGroup increment prevents Add racing with
// Stop's Wait: once Stop flips stopped, no new worker can enter the group.
func (r *Runtime) launch(work func()) bool {
	if r == nil || work == nil {
		return false
	}
	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		return false
	}
	r.workers.Add(1)
	r.mu.Unlock()
	go func() {
		defer r.workers.Done()
		defer r.recoverWorker()
		work()
	}()
	return true
}

// recoverWorker keeps one broken action from killing the whole Android process.
//
// Go terminates the process on an unrecovered panic, and every runtime goroutine
// runs under launch: the HTTP server, the inbound loops, media cleanup, each
// action. Without this, a nil dereference anywhere in the transport took the
// foreground service down with it, which reads to the user as "the bot died" and
// costs a full reconnect. Degrading to one lost operation is strictly better.
//
// The frame is published, not just logged, so the operator sees that something
// aborted rather than wondering why one message never arrived. It carries a
// bounded classification only — never a JID, payload or stack trace.
func (r *Runtime) recoverWorker() {
	panicked := recover()
	if panicked == nil {
		return
	}
	newAndroidLogger("nativewa-runtime", "ERROR").Errorf("runtime worker panicked")
	// Best effort and deliberately last: if publishing itself is what broke, the
	// log line above is still on record.
	_ = r.publish(map[string]any{
		"type":   "safety",
		"kind":   "runtime_worker_panic",
		"detail": "native_runtime_worker_panic",
	})
}

// wa returns the current WhatsApp client under the runtime lock.
//
// The field is written on relink and read from every publisher, action and media
// goroutine. Reading it without the lock is a data race under the Go memory model:
// the write is not guaranteed to be visible, so a caller can still see the previous
// client — or nil — long after the relink finished. Every read outside a section
// that already holds mu goes through here.
func (r *Runtime) wa() *whatsmeow.Client {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.waClient
}

// deviceStore returns the current linked-device store under the same lock as wa(). Relinking
// replaces both pointers together; direct reads raced that replacement and could mix the old
// device identity with the new client.
func (r *Runtime) deviceStore() *waStore.Device {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.device
}

func (r *Runtime) baseContext() context.Context {
	if r != nil && r.ctx != nil {
		return r.ctx
	}
	return context.Background()
}

func (r *Runtime) contextWithTimeout(timeout time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(r.baseContext(), timeout)
}

// BaseURL is useful for diagnostics and always resolves to Android loopback.
func (r *Runtime) BaseURL() string {
	if r == nil || r.listener == nil {
		return ""
	}
	return "http://" + r.listener.Addr().String() + "/v1"
}

func (r *Runtime) handleSocket(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	conn, err := websocket.Accept(w, req, &websocket.AcceptOptions{
		InsecureSkipVerify: true, // listener is loopback-only; bearer auth remains mandatory
	})
	if err != nil {
		return
	}
	session := &wsSession{conn: conn}
	r.mu.Lock()
	if r.stopped {
		r.mu.Unlock()
		_ = conn.Close(websocket.StatusGoingAway, "stopped")
		return
	}
	r.sessions[conn] = session
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.sessions, conn)
		r.mu.Unlock()
		_ = conn.Close(websocket.StatusNormalClosure, "closed")
	}()

	ctx := req.Context()
	for {
		kind, raw, readErr := conn.Read(ctx)
		if readErr != nil {
			return
		}
		if kind != websocket.MessageText || len(raw) > 1_000_000 {
			_ = conn.Close(websocket.StatusUnsupportedData, "text frames only")
			return
		}
		if err = r.handleClientFrame(ctx, session, raw); err != nil {
			_ = session.writeJSON(map[string]any{
				"v":         protocolVersion,
				"type":      "error",
				"code":      "invalid_frame",
				"message":   "Native bridge rejected a client frame",
				"retryable": false,
			})
			_ = conn.Close(websocket.StatusPolicyViolation, "invalid frame")
			return
		}
	}
}

func (r *Runtime) handleClientFrame(ctx context.Context, session *wsSession, raw []byte) error {
	var frame clientFrame
	if err := json.Unmarshal(raw, &frame); err != nil {
		return err
	}
	if frame.Version != protocolVersion {
		return fmt.Errorf("unsupported protocol version")
	}
	if !session.isReady() {
		if frame.Type != "hello" || strings.TrimSpace(frame.ClientID) == "" {
			return fmt.Errorf("hello required")
		}
		return r.completeHello(session, frame)
	}
	switch frame.Type {
	case "ack_event":
		return nil
	case "action":
		if len(frame.ID) < 8 || len(frame.Action) == 0 {
			return fmt.Errorf("invalid action frame")
		}
		// Apply backpressure to the one authenticated loopback client instead of allocating an
		// unbounded goroutine for every frame during a malfunction or reconnect burst.
		select {
		case r.actionSlots <- struct{}{}:
		case <-ctx.Done():
			return ctx.Err()
		case <-r.shutdown:
			return context.Canceled
		}
		if !r.launch(func() {
			defer func() { <-r.actionSlots }()
			r.handleAction(session, frame)
		}) {
			<-r.actionSlots
			return context.Canceled
		}
		return nil
	default:
		return fmt.Errorf("unknown client frame")
	}
}

func validBridgeToken(raw string) bool {
	value := strings.TrimSpace(raw)
	if len(value) >= 64 && len(value)%2 == 0 {
		if decoded, err := hex.DecodeString(value); err == nil && len(decoded) >= 32 {
			return true
		}
	}
	if len(value) < 43 || strings.Contains(value, "=") {
		return false
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return err == nil && len(decoded) >= 32
}

func (r *Runtime) completeHello(session *wsSession, hello clientFrame) error {
	// Held until the session is ready so that the bounds this handshake
	// promises still describe the journal when the first live frame arrives.
	// Every write below is deadline-bounded, so a stalled client cannot hold
	// event publication hostage.
	r.publishes.Lock()
	defer r.publishes.Unlock()

	bounds, err := r.journal.bounds()
	if err != nil {
		return err
	}
	requested := hello.ResumeAfter
	reset := requested > bounds.Latest
	after := requested
	if reset {
		after = bounds.Latest
	}
	gap := bounds.Count > 0 && after < bounds.Oldest-1
	if gap {
		after = bounds.Oldest - 1
	}
	pending, err := r.journal.replayCount(after)
	if err != nil {
		return err
	}
	account := r.accountSnapshot()
	if err = session.writeJSON(map[string]any{
		"v":            protocolVersion,
		"type":         "welcome",
		"sequence":     bounds.Latest,
		"capabilities": serverCapabilities,
		"account":      account,
		"resume": map[string]any{
			"requested": requested,
			"after":     after,
			"oldest":    bounds.Oldest,
			"latest":    bounds.Latest,
			"count":     pending,
			"gap":       gap,
			"reset":     reset,
		},
	}); err != nil {
		return err
	}
	if _, err = r.journal.replayEach(after, session.write); err != nil {
		return err
	}
	session.clientID = hello.ClientID
	session.setReady(true)
	return session.writeJSON(map[string]any{
		"v":        protocolVersion,
		"type":     "ready",
		"sequence": bounds.Latest,
		"account":  account,
	})
}

func (r *Runtime) publish(frame map[string]any) error {
	// The whole publication is serialized, for two reasons. Against a running
	// handshake, because a frame appended after that handshake read its bounds
	// and before the session is ready reaches neither the replay nor the live
	// broadcast. And against another publisher, because sequence numbers are
	// assigned by the journal while delivery order is decided by whoever
	// reaches the socket first — several goroutines publish concurrently
	// (blocklist refresh, forced reconnect, post-connect activation), so
	// without this the client could observe 212 before 211 and reject the
	// stream as a gap. Every write below is deadline-bounded.
	r.publishes.Lock()
	defer r.publishes.Unlock()
	r.mu.RLock()
	stopped := r.stopped
	r.mu.RUnlock()
	if stopped {
		return context.Canceled
	}
	raw, _, err := r.journal.append(frame)
	if err != nil {
		return fmt.Errorf("append native event: %w", err)
	}
	r.mu.RLock()
	sessions := make([]*wsSession, 0, len(r.sessions))
	for _, session := range r.sessions {
		if session.isReady() {
			sessions = append(sessions, session)
		}
	}
	r.mu.RUnlock()
	for _, session := range sessions {
		if session.write(raw) != nil {
			_ = session.conn.Close(websocket.StatusInternalError, "write failed")
		}
	}
	return nil
}

func (s *wsSession) writeJSON(value any) error {
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return s.write(raw)
}

func (s *wsSession) write(raw []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.conn.Write(ctx, websocket.MessageText, raw)
}

func (r *Runtime) authorized(req *http.Request) bool {
	value := strings.TrimSpace(req.Header.Get("Authorization"))
	expected := "Bearer " + r.token
	if len(value) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(value), []byte(expected)) == 1
}

func (r *Runtime) handleMediaUpload(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if req.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	r.media.uploadHTTP(w, req)
}

func (r *Runtime) handleMediaDownload(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if req.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := strings.TrimPrefix(req.URL.Path, "/v1/media/")
	r.media.downloadHTTP(w, req, id)
}

func (r *Runtime) handleHealth(w http.ResponseWriter, req *http.Request) {
	if !r.authorized(req) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":      true,
		"mode":    "native_linked_device",
		"account": r.accountSnapshot(),
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	raw, err := json.Marshal(value)
	if err != nil {
		http.Error(w, "json error", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(raw)
}

func (r *Runtime) accountSnapshot() map[string]any {
	r.mu.RLock()
	defer r.mu.RUnlock()
	state := "pairing"
	if r.waClient != nil && r.waClient.IsConnected() && r.waClient.IsLoggedIn() {
		state = "connected"
	} else if r.device != nil && r.device.ID != nil {
		state = "connecting"
	}
	account := map[string]any{
		"state":  state,
		"paired": r.device != nil && r.device.ID != nil,
	}
	if r.device != nil && r.device.ID != nil {
		account["jid"] = normalizeJID(*r.device.ID)
		if r.device.PushName != "" {
			account["name"] = r.device.PushName
		}
	}
	return account
}

func publicError(err error) map[string]any {
	code, message := classifyActionError(err)
	return map[string]any{
		"code":    code,
		"message": message,
	}
}
