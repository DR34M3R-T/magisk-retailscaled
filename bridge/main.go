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
	listenAddr    string
	port          int
	socketPath    string
	tailscaleBin  string
	serviceBin    string
	modPropPath   string
	vpnBypassFile string
	appPkg        string
	tokenPath     string
}

type authFilePayload struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
}

type bridgeServer struct {
	cfg        bridgeConfig
	token      string
	unixClient *http.Client

	webMu  sync.Mutex
	webCmd *exec.Cmd
}

func main() {
	var cfg bridgeConfig
	flag.IntVar(&cfg.port, "port", 41688, "local loopback HTTP port")
	flag.StringVar(&cfg.socketPath, "socket", "/data/adb/tailscale/tailscaled.sock", "path to tailscaled unix socket")
	flag.StringVar(&cfg.tailscaleBin, "tailscale", "/data/adb/tailscale/bin/tailscale", "path to tailscale CLI")
	flag.StringVar(&cfg.serviceBin, "service", "/data/adb/tailscale/scripts/tailscaled.service", "path to tailscaled.service")
	flag.StringVar(&cfg.modPropPath, "modprop", "/data/adb/modules/magisk-retailscaled/module.prop", "path to module.prop")
	flag.StringVar(&cfg.vpnBypassFile, "vpn-bypass-file", "/data/adb/tailscale/vpn_bypass", "path to Android VPN bypass toggle file")
	flag.StringVar(&cfg.appPkg, "app", "io.github.dr34m3rt.retailscaled", "Android package name allowed to read token")
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
	mux.HandleFunc("/api/vpn-bypass", srv.withAuth(srv.handleVpnBypass))
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
	data, err := os.ReadFile(s.cfg.modPropPath)
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "version=") {
			return strings.TrimSpace(strings.TrimPrefix(line, "version="))
		}
	}
	return ""
}

func (s *bridgeServer) readVpnBypass() bool {
	data, err := os.ReadFile(s.cfg.vpnBypassFile)
	if err != nil {
		return true
	}
	switch strings.ToLower(strings.TrimSpace(string(data))) {
	case "0", "false", "off", "no":
		return false
	default:
		return true
	}
}

func (s *bridgeServer) writeVpnBypass(enabled bool) error {
	if err := os.MkdirAll(filepath.Dir(s.cfg.vpnBypassFile), 0755); err != nil {
		return err
	}
	val := "1\n"
	if !enabled {
		val = "0\n"
	}
	return os.WriteFile(s.cfg.vpnBypassFile, []byte(val), 0644)
}

func (s *bridgeServer) serviceEnv(extra ...string) []string {
	bypassVal := "1"
	if !s.readVpnBypass() {
		bypassVal = "0"
	}
	env := make([]string, 0, len(os.Environ())+len(extra)+1)
	for _, kv := range os.Environ() {
		if strings.HasPrefix(kv, "TS_BYPASS_VPN=") {
			continue
		}
		env = append(env, kv)
	}
	env = append(env, "TS_BYPASS_VPN="+bypassVal)
	env = append(env, extra...)
	return env
}

func (s *bridgeServer) isDaemonRunning() bool {
	conn, err := net.DialTimeout("unix", s.cfg.socketPath, 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func (s *bridgeServer) waitForDaemon(attempts int, interval time.Duration) bool {
	for i := 0; i < attempts; i++ {
		if s.isDaemonRunning() {
			return true
		}
		time.Sleep(interval)
	}
	return false
}

func (s *bridgeServer) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"moduleVersion": s.readModuleVersion(),
		"vpnBypass":     s.readVpnBypass(),
	})
}

func (s *bridgeServer) handleVpnBypass(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":      true,
			"enabled": s.readVpnBypass(),
		})
		return
	}
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid JSON"})
		return
	}
	if err := s.writeVpnBypass(body.Enabled); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	restarted := false
	outMsg := ""
	if s.isDaemonRunning() {
		cmd := exec.Command(s.cfg.serviceBin, "restart")
		cmd.Env = s.serviceEnv()
		out, err := cmd.CombinedOutput()
		outMsg = strings.TrimSpace(string(out))
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"ok":        false,
				"enabled":   body.Enabled,
				"restarted": false,
				"error":     strings.TrimSpace(outMsg + "\n" + err.Error()),
			})
			return
		}
		restarted = s.waitForDaemon(15, 400*time.Millisecond)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        true,
		"enabled":   body.Enabled,
		"restarted": restarted,
		"output":    outMsg,
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
	if s.isDaemonRunning() {
		return
	}

	cmd := exec.Command(s.cfg.serviceBin, "start")
	cmd.Env = s.serviceEnv("TS_SOCKS5_ADDR=" + socks5)
	_ = cmd.Run()

	s.waitForDaemon(12, 400*time.Millisecond)
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

func (s *bridgeServer) fetchHealthErrors(ctx context.Context) []string {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://local-tailscaled.sock/localapi/v0/status", nil)
	if err != nil {
		return nil
	}
	resp, err := s.unixClient.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	var st struct {
		Health []string `json:"Health"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&st); err != nil {
		return nil
	}
	return st.Health
}

func (s *bridgeServer) handleBrowserLogin(w http.ResponseWriter, r *http.Request) {
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

	args := []string{"login"}
	if h := strings.TrimSpace(body.Hostname); h != "" {
		args = append(args, "--hostname="+h)
	}
	if body.AcceptDnsFalse {
		args = append(args, "--accept-dns=false")
	}

	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
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

	var (
		lines    []string
		foundURL bool
	)
	flusher, _ := w.(http.Flusher)

	scanner := bufio.NewScanner(stdout)
	for scanner.Scan() {
		line := scanner.Text()
		lines = append(lines, line)
		if strings.Contains(line, "https://") || strings.Contains(line, "http://") {
			foundURL = true
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Header().Set("Cache-Control", "no-cache")
			w.WriteHeader(http.StatusOK)
			for _, buffered := range lines {
				_, _ = fmt.Fprintln(w, buffered)
			}
			if flusher != nil {
				flusher.Flush()
			}
			// AuthURL is already registered in tailscaled; stop the blocking CLI immediately
			cancel()
			break
		}
	}
	_ = cmd.Wait()

	if foundURL {
		return
	}

	var errParts []string
	if out := strings.TrimSpace(strings.Join(lines, "\n")); out != "" && out != "context canceled" && out != "context deadline exceeded" {
		errParts = append(errParts, out)
	}
	healthCtx, healthCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer healthCancel()
	if health := s.fetchHealthErrors(healthCtx); len(health) > 0 {
		errParts = append(errParts, strings.Join(health, "\n"))
	}
	if len(errParts) == 0 {
		errParts = append(errParts, "获取登录链接超时，请检查网络或代理连通性")
	}
	http.Error(w, strings.Join(errParts, "\n"), http.StatusBadGateway)
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
