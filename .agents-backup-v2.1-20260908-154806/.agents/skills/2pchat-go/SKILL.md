---
name: 2pchat-go
description: Develop and review the Go Core of 2PChat with strict concurrency, memory-lifetime, error-handling and security validation.
---

# 2PChat Go Core

Inspect goroutines, channels, locks, atomics, contexts, cancellation, ownership, resource lifetime and shutdown.

Run as applicable:
```bash
go test ./...
go test -race ./...
go vet ./...
staticcheck ./...
govulncheck ./...
```

Treat externally controlled data as hostile.
Do not ignore errors in security-sensitive paths.
