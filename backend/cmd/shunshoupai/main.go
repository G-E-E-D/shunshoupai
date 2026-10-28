package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"shunshoupai/backend/internal/app"
	"strings"
	"syscall"
	"time"
)

var version = "dev"

func main() {
	versionFlag := flag.Bool("version", false, "print version")
	port := flag.String("port", env("PORT", "3000"), "HTTP port")
	host := flag.String("host", env("APP_LISTEN_HOST", "0.0.0.0"), "HTTP interface")
	dbPath := flag.String("db", env("APP_DB_PATH", filepath.Join("data", "shunshoupai.sqlite")), "SQLite database path")
	web := flag.String("web", env("APP_WEB_DIR", "out"), "static frontend directory")
	flag.Parse()
	if *versionFlag {
		fmt.Println(version)
		return
	}
	if storage := os.Getenv("APP_STORAGE"); storage != "" && storage != "local" {
		log.Fatal("Go backend requires APP_STORAGE=local")
	}
	store, err := app.OpenStore(*dbPath)
	if err != nil {
		log.Fatal("Could not open application database")
	}
	defer store.Close()
	handler := app.NewServer(store, *web)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	handler.StartPeerWorker(ctx)
	server := &http.Server{Addr: net.JoinHostPort(*host, *port), Handler: handler, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 90 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32768}
	go func() {
		<-ctx.Done()
		shutdown, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		_ = server.Shutdown(shutdown)
	}()
	log.Printf("Shunshoupai %s Go service listening on %s", version, server.Addr)
	if err = server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal("HTTP listener failed")
	}
}
func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}
