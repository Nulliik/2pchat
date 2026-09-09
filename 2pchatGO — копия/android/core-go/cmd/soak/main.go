package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

type Config struct {
	Duration   time.Duration
	Nodes      int
	ReportPath string
	Scenarios  []string
	FlapRate   float64 // network flap probability per minute (0.0-1.0)
	ClockSkew  bool    // enable clock skew on 30% of nodes
	Partition  bool    // enable periodic network partition simulation
}

func main() {
	// Enable loopback endpoints in candidate filters
	_ = os.Setenv("GO_TEST", "1")

	cfg := parseFlags()

	log.Printf("Starting soak: %d nodes, duration=%v, scenarios=%v",
		cfg.Nodes, cfg.Duration, cfg.Scenarios)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Graceful shutdown handling
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Println("Shutdown signal received, stopping soak...")
		cancel()
	}()

	// 1. Initialize nodes
	nodes, err := createNodes(ctx, cfg.Nodes)
	if err != nil {
		log.Fatalf("Failed to create nodes: %v", err)
	}
	defer cleanupNodes(nodes)

	// 2. Establish pairwise sessions (full mesh)
	if err := establishFullMesh(ctx, nodes); err != nil {
		log.Fatalf("Failed to establish mesh: %v", err)
	}

	// 3. Start metrics collector
	reporter := NewMetricsReporter(cfg.ReportPath)
	go reporter.Collect(ctx, nodes, 10*time.Second)

	// 4. Start scenarios
	var wg sync.WaitGroup
	for _, scenarioName := range cfg.Scenarios {
		scenario, err := getScenario(scenarioName)
		if err != nil {
			log.Fatalf("Unknown scenario: %s", scenarioName)
		}
		wg.Add(1)
		go func(s Scenario) {
			defer wg.Done()
			s.Run(ctx, nodes, cfg)
		}(scenario)
	}

	// Optional: network flap
	if cfg.FlapRate > 0 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			runNetworkFlap(ctx, nodes, cfg.FlapRate)
		}()
	}

	// Optional: network partition
	if cfg.Partition {
		wg.Add(1)
		go func() {
			defer wg.Done()
			runNetworkPartition(ctx, nodes, 15*time.Second)
		}()
	}

	// Optional: clock skew
	if cfg.ClockSkew {
		runClockSkew(ctx, nodes, 0.3)
	}

	// Wait for duration or cancellation
	select {
	case <-ctx.Done():
		log.Println("Context cancelled")
	case <-time.After(cfg.Duration):
		log.Println("Duration reached")
	}

	cancel()
	wg.Wait()

	// 5. Final report
	report, err := reporter.GenerateFinalReport()
	if err != nil {
		log.Fatalf("Failed to generate report: %v", err)
	}

	// 6. Check pass/fail criteria
	if len(report.Failures) > 0 {
		log.Printf("FAIL: %d criteria failed:", len(report.Failures))
		for _, f := range report.Failures {
			log.Printf("  - %s", f)
		}
		os.Exit(1)
	}

	log.Printf("PASS: all soak criteria met. Report saved to: %s", cfg.ReportPath)
}

func parseFlags() Config {
	duration := flag.Duration("duration", 6*time.Hour, "Soak duration")
	nodes := flag.Int("nodes", 12, "Number of nodes")
	report := flag.String("report", "soak-report.json", "Report output path")
	scenarios := flag.String("scenarios", "pairwise,group,succession", "Comma-separated scenarios")
	flapRate := flag.Float64("flap-rate", 0.1, "Network flap rate per minute (0.0-1.0)")
	clockSkew := flag.Bool("clock-skew", true, "Enable clock skew on 30% of nodes")
	partition := flag.Bool("partition", false, "Enable periodic network partition simulation")

	flag.Parse()

	return Config{
		Duration:   *duration,
		Nodes:      *nodes,
		ReportPath: *report,
		Scenarios:  splitCSV(*scenarios),
		FlapRate:   *flapRate,
		ClockSkew:  *clockSkew,
		Partition:  *partition,
	}
}

func splitCSV(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		if trimmed := strings.TrimSpace(p); trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}
