package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
)

type bridgeConfig struct {
	listenAddr   string
	port         int
	socketPath   string
	tailscaleBin string
	serviceBin   string
	modPropPath  string
	appPkg       string
	tokenPath    string
}

type authFilePayload struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
}

type bridgeServer struct {
	cfg        bridgeConfig
	token      string
	unixClient *http.Client

	webMu   sync.Mutex
	webCmd  *exec.Cmd
	webPort int
}

func main() {
	var cfg bridgeConfig
	flag.IntVar(&cfg.port, "port", 41688, "local loopback HTTP port")
	flag.StringVar(&cfg.socketPath, "socket", "/data/adb/tailscale/tailscaled.sock", "path to tailscaled unix socket")
	flag.StringVar(&cfg.tailscaleBin, "tailscale", "/data/adb/tailscale/bin/tailscale", "path to tailscale CLI")
	flag.StringVar(&cfg.serviceBin, "service", "/data/adb/tailscale/scripts/tailscaled.service", "path to tailscaled.service")
	flag.StringVar(&cfg.modPropPath, "modprop", "/data/adb/modules/magisk-retailscaled/module.prop", "path to module.prop")
	flag.StringVar(&cfg.appPkg, "app", "com.example.roottailscale", "Android package name allowed to read token")
	flag.StringVar(&cfg.tokenPath, "token-file", "/data/adb/tailscale/run/bridge.token", "internal token path")
	flag.Parse()

	cfg.listenAddr = fmt.Sprintf("127.0.0.1:%d", cfg.port)

	token, err := loadOrCreateToken(cfg.tokenPath)
	if err != nil {
		log.Fatalf("failed to initialize token: %v", err)
	}

	// Sync token to app private files dir (retry in background if app not installed yet)
	go syncTokenLoop(cfg.appPkg, cfg.port, token)

	unixTransport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", cfg.socketPath)
		},
	}

	srv := &bridgeServer{
		cfg:   cfg,
		token: token,
		unixClient: &http.Client{
			Transport: unixTransport,
			Timeout:   8 * time.Second,
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/health", srv.withAuth(srv.handleHealth))
	mux.HandleFunc("/api/status", srv.withAuth(srv.handleStatus))
	mux.HandleFunc("/api/up", srv.withAuth(srv.handleUp))
	mux.HandleFunc("/api/down", srv.withAuth(srv.handleDown))
	mux.HandleFunc("/api/login/browser", srv.withAuth(srv.handleBrowserLogin))
	mux.HandleFunc("/api/login/authkey", srv.withAuth(srv.handleAuthKeyLogin))
	mux.HandleFunc("/api/ping", srv.withAuth(srv.handlePing))
	mux.HandleFunc("/api/exit-node", srv.withAuth(srv.handleExitNode))
	mux.HandleFunc("/api/webui/start", srv.withAuth(srv.handleWebUiStart))
	mux.HandleFunc("/api/webui/stop", srv.withAuth(srv.handleWebUiStop))

	httpServer := &http.Server{
		Addr:              cfg.listenAddr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	log.Printf("tailscaled-bridge listening on %s", cfg.listenAddr)
	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatalf("server error: %v", err)
	}
}

func loadOrCreateToken(path string) (string, error) {
	_ = os.MkdirAll(filepath.Dir(path), 0700)
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	_ = os.WriteFile(path, []byte(token+"\n"), 0600)
	return token, nil
}

func syncTokenLoop(pkg string, port int, token string) {
	if tryWriteAppToken(pkg, port, token) {
		return
	}
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for i := 0; i < 60; i++ {
		<-ticker.C
		if tryWriteAppToken(pkg, port, token) {
			return
		}
	}
}

func tryWriteAppToken(pkg string, port int, token string) bool {
	baseDir := filepath.Join("/data/user/0", pkg)
	info, err := os.Stat(baseDir)
	if err != nil {
		return false
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return false
	}
	uid := int(stat.Uid)
	gid := int(stat.Gid)

	filesDir := filepath.Join(baseDir, "files")
	if err := os.MkdirAll(filesDir, 0700); err != nil {
		return false
	}
	_ = os.Chown(filesDir, uid, gid)

	payload, err := json.Marshal(authFilePayload{Port: port, Token: token})
	if err != nil {
		return false
	}
	target := filepath.Join(filesDir, "bridge_auth.json")
	if err := os.WriteFile(target, payload, 0600); err != nil {
		return false
	}
	_ = os.Chown(target, uid, gid)
	_ = os.Chmod(target, 0600)
	// Restore SELinux context so app can read its own private file on strict ROMs
	_ = exec.Command("restorecon", target).Run()
	return true
}

func (s *bridgeServer) withAuth(next http.HandlerFunc) http.HandlerFunc {
	expected := "Bearer " + s.token
	return func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("Authorization")
		if subtle.ConstantTimeCompare([]byte(got), []byte(expected)) != 1 {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func (s *bridgeServer) readModuleVersion() string {
	paths := []string{s.cfg.modPropPath, "/data/adb/modules/magisk-tailscaled/module.prop"}
	for _, p := range paths {
		data, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(data), "\n") {
			if strings.HasPrefix(line, "version=") {
				return strings.TrimSpace(strings.TrimPrefix(line, "version="))
			}
		}
	}
	return ""
}

func (s *bridgeServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"moduleVersion": s.readModuleVersion(),
	})
}

func (s *bridgeServer) handleStatus(w http.ResponseWriter, r *http.Request) {
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, "http://local-tailscaled.sock/localapi/v0/status", nil)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	resp, err := s.unixClient.Do(req)
	if err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"daemonStopped": true,
			"detail":        err.Error(),
		})
		return
	}
	defer resp.Body.Close()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func (s *bridgeServer) ensureDaemon(socks5 string) {
	// Quick probe via unix socket
	conn, err := net.DialTimeout("unix", s.cfg.socketPath, 500*time.Millisecond)
	if err == nil {
		_ = conn.Close()
		return
	}

	cmd := exec.Command(s.cfg.serviceBin, "start")
	cmd.Env = append(os.Environ(), "TS_SOCKS5_ADDR="+socks5)
	_ = cmd.Run()

	for i := 0; i < 12; i++ {
		time.Sleep(400 * time.Millisecond)
		c, e := net.DialTimeout("unix", s.cfg.socketPath, 300*time.Millisecond)
		if e == nil {
			_ = c.Close()
			return
		}
	}
}

func (s *bridgeServer) handleUp(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AcceptDnsFalse bool   `json:"acceptDnsFalse"`
		Hostname       string `json:"hostname"`
		Socks5Address  string `json:"socks5Address"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	socks := strings.TrimSpace(body.Socks5Address)
	if socks == "" {
		socks = "127.0.0.1:1055"
	}
	s.ensureDaemon(socks)

	args := []string{"up"}
	if h := strings.TrimSpace(body.Hostname); h != "" {
		args = append(args, "--hostname="+h)
	}
	if body.AcceptDnsFalse {
		args = append(args, "--accept-dns=false")
	}

	out, code := s.runTailscale(20*time.Second, args...)
	writeJSON(w, http.StatusOK, map[string]any{
		"exitCode": code,
		"output":   out,
	})
}

func (s *bridgeServer) handleDown(w http.ResponseWriter, r *http.Request) {
	var body struct {
		DeepPowerSave bool `json:"deepPowerSave"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	out, code := s.runTailscale(15*time.Second, "down")
	if body.DeepPowerSave {
		stopCmd := exec.Command(s.cfg.serviceBin, "stop")
		stopOut, err := stopCmd.CombinedOutput()
		if err == nil || code == 0 {
			writeJSON(w, http.StatusOK, map[string]any{
				"exitCode": 0,
				"output":   "已停止 Tailscale 及 tailscaled 进程",
			})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"exitCode": 1,
			"output":   strings.TrimSpace(out + "\n" + string(stopOut)),
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"exitCode": code,
		"output":   out,
	})
}

func (s *bridgeServer) handleBrowserLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Hostname string `json:"hostname"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	args := []string{"login"}
	if h := strings.TrimSpace(body.Hostname); h != "" {
		args = append(args, "--hostname="+h)
	}
	s.streamTailscale(w, r.Context(), 15*time.Second, args...)
}

func (s *bridgeServer) handleAuthKeyLogin(w http.ResponseWriter, r *http.Request) {
	var body struct {
		AuthKey        string `json:"authKey"`
		LoginServer    string `json:"loginServer"`
		AcceptDnsFalse bool   `json:"acceptDnsFalse"`
		Hostname       string `json:"hostname"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"exitCode": 1, "output": "invalid JSON"})
		return
	}
	args := []string{"up", "--authkey=" + strings.TrimSpace(body.AuthKey)}
	if h := strings.TrimSpace(body.Hostname); h != "" {
		args = append(args, "--hostname="+h)
	}
	if srv := strings.TrimSpace(body.LoginServer); srv != "" {
		args = append(args, "--login-server="+srv)
	}
	if body.AcceptDnsFalse {
		args = append(args, "--accept-dns=false")
	}

	out, code := s.runTailscale(30*time.Second, args...)
	writeJSON(w, http.StatusOK, map[string]any{
		"exitCode": code,
		"output":   out,
	})
}

func (s *bridgeServer) handlePing(w http.ResponseWriter, r *http.Request) {
	var body struct {
		IP    string `json:"ip"`
		Count int    `json:"count"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.IP) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing ip"})
		return
	}
	count := body.Count
	if count <= 0 || count > 10 {
		count = 3
	}
	s.streamTailscale(w, r.Context(), 20*time.Second, "ping", "-c", fmt.Sprintf("%d", count), strings.TrimSpace(body.IP))
}

func (s *bridgeServer) handleExitNode(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ExitNode string `json:"exitNode"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	out, code := s.runTailscale(15*time.Second, "set", "--exit-node="+strings.TrimSpace(body.ExitNode))
	writeJSON(w, http.StatusOK, map[string]any{
		"exitCode": code,
		"output":   out,
	})
}

func (s *bridgeServer) handleWebUiStart(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Port int `json:"port"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	port := body.Port
	if port < 1024 || port > 65535 {
		port = 8088
	}

	s.stopWebUiLocked()

	listen := fmt.Sprintf("127.0.0.1:%d", port)
	cmd := exec.Command(s.cfg.tailscaleBin, "web", "--listen", listen)
	if err := cmd.Start(); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	s.webMu.Lock()
	s.webCmd = cmd
	s.webPort = port
	s.webMu.Unlock()

	go func() { _ = cmd.Wait() }()

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":  true,
		"url": "http://" + listen,
	})
}

func (s *bridgeServer) handleWebUiStop(w http.ResponseWriter, r *http.Request) {
	s.stopWebUiLocked()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *bridgeServer) stopWebUiLocked() {
	s.webMu.Lock()
	defer s.webMu.Unlock()
	if s.webCmd != nil && s.webCmd.Process != nil {
		_ = s.webCmd.Process.Kill()
		s.webCmd = nil
	}
}

func (s *bridgeServer) runTailscale(timeout time.Duration, args ...string) (string, int) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, s.cfg.tailscaleBin, args...)
	out, err := cmd.CombinedOutput()
	if err != nil {
		if exitErr, ok := err.(*exec.ExitError); ok {
			return strings.TrimSpace(string(out)), exitErr.ExitCode()
		}
		return strings.TrimSpace(string(out) + "\n" + err.Error()), 1
	}
	return strings.TrimSpace(string(out)), 0
}

func (s *bridgeServer) streamTailscale(w http.ResponseWriter, reqCtx context.Context, timeout time.Duration, args ...string) {
	ctx, cancel := context.WithTimeout(reqCtx, timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, s.cfg.tailscaleBin, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	cmd.Stderr = cmd.Stdout

	if err := cmd.Start(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)

	flusher, _ := w.(http.Flusher)
	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		_, _ = fmt.Fprintln(w, scanner.Text())
		if flusher != nil {
			flusher.Flush()
		}
	}
	_ = cmd.Wait()
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
