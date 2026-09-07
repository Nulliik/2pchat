package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Metrics represents a node metrics sample.
type Metrics struct {
	Timestamp      time.Time `json:"timestamp"`
	NodeID         int       `json:"node_id"`
	RSSBytes       int64     `json:"rss_bytes"`
	HeapAllocBytes uint64    `json:"heap_alloc_bytes"`
	HeapObjects    uint64    `json:"heap_objects"`
	Goroutines     int       `json:"goroutines"`
	OpenFDs        int       `json:"open_fds"`
	SentMessages   int64     `json:"sent_messages"`
	RecvMessages   int64     `json:"recv_messages"`
	Duplicates     int64     `json:"duplicates"`
	OutboxSize     int64     `json:"outbox_size"`
	Panics         int       `json:"panics"`
	RaceWarnings   int       `json:"race_warnings"`
}

// MetricsSnapshot represents aggregated metrics across all active nodes.
type MetricsSnapshot struct {
	Timestamp       time.Time `json:"timestamp"`
	Nodes           []Metrics `json:"nodes"`
	TotalRSS        int64     `json:"total_rss_bytes"`
	TotalHeap       uint64    `json:"total_heap_bytes"`
	TotalGoroutines int       `json:"total_goroutines"`
	TotalSent       int64     `json:"total_sent_messages"`
	TotalRecv       int64     `json:"total_recv_messages"`
	TotalDups       int64     `json:"total_duplicates"`
	TotalOutbox     int64     `json:"total_outbox_size"`
}

// SoakReport is the final test report.
type SoakReport struct {
	StartTime   time.Time         `json:"start_time"`
	EndTime     time.Time         `json:"end_time"`
	Duration    time.Duration     `json:"duration"`
	NodeCount   int               `json:"node_count"`
	Scenarios   []string          `json:"scenarios"`
	InitialSnap MetricsSnapshot   `json:"initial_snapshot"`
	FinalSnap   MetricsSnapshot   `json:"final_snapshot"`
	History     []MetricsSnapshot `json:"history"`
	PassFail    map[string]bool   `json:"pass_fail"`
	Failures    []string          `json:"failures"`
	Summary     ReportSummary     `json:"summary"`
}

// ReportSummary aggregates data for evaluation and markdown reports.
type ReportSummary struct {
	MaxRSSBytes        int64   `json:"max_rss_bytes"`
	AvgRSSBytes        int64   `json:"avg_rss_bytes"`
	RSSGrowthRateMBpH  float64 `json:"rss_growth_rate_mb_per_hour"`
	MaxGoroutines      int     `json:"max_goroutines"`
	GoroutineGrowthPct float64 `json:"goroutine_growth_percent"`
	MaxFDs             int     `json:"max_fds"`
	FDGrowth           int     `json:"fd_growth"`
	MessagesSent       int64   `json:"messages_sent"`
	MessagesRecv       int64   `json:"messages_received"`
	MessagesLost       int64   `json:"messages_lost"`
	MessagesDuplicated int64   `json:"messages_duplicated"`
	TotalPanics        int     `json:"total_panics"`
	TotalRaces         int     `json:"total_races"`
}

// Pass/fail thresholds
const (
	RSSGrowthLimitMBpH      = 10.0 // MB/hour
	GoroutineGrowthLimitPct = 5.0  // % per hour
	FDGrowthLimitPerHour    = 5
	MaxPanics               = 0
	MaxRaces                = 0
	MaxMessageLoss          = 0
	MaxMessageDuplication   = 0
	MaxOutboxDrainDuration  = 5 * time.Minute
)

// MetricsReporter collects metrics periodically and generates final reports.
type MetricsReporter struct {
	reportPath string
	startTime  time.Time
	history    []MetricsSnapshot
	mu         sync.Mutex
}

func NewMetricsReporter(reportPath string) *MetricsReporter {
	return &MetricsReporter{
		reportPath: reportPath,
		startTime:  time.Now(),
		history:    make([]MetricsSnapshot, 0, 1024),
	}
}

// Collect samples metrics from all nodes at every interval.
func (r *MetricsReporter) Collect(ctx context.Context, nodes []*Node, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			snap := r.collectSnapshot(nodes)
			r.mu.Lock()
			r.history = append(r.history, snap)
			r.mu.Unlock()

			if len(r.history)%6 == 0 {
				log.Printf("[metrics] RSS=%dMB heap=%dMB gor=%d sent=%d recv=%d outbox=%d",
					snap.TotalRSS/(1024*1024),
					snap.TotalHeap/(1024*1024),
					snap.TotalGoroutines,
					snap.TotalSent,
					snap.TotalRecv,
					snap.TotalOutbox,
				)
			}
		}
	}
}

func (r *MetricsReporter) collectSnapshot(nodes []*Node) MetricsSnapshot {
	snap := MetricsSnapshot{
		Timestamp: time.Now(),
		Nodes:     make([]Metrics, 0, len(nodes)),
	}

	for _, n := range nodes {
		if n == nil {
			continue
		}
		m := collectNodeMetrics(n)
		snap.Nodes = append(snap.Nodes, m)
		snap.TotalRSS += m.RSSBytes
		snap.TotalHeap += m.HeapAllocBytes
		snap.TotalGoroutines += m.Goroutines
		snap.TotalSent += m.SentMessages
		snap.TotalRecv += m.RecvMessages
		snap.TotalDups += m.Duplicates
		snap.TotalOutbox += m.OutboxSize
	}

	return snap
}

func collectNodeMetrics(n *Node) Metrics {
	m := Metrics{
		Timestamp:  time.Now(),
		NodeID:     n.ID,
		Goroutines: runtime.NumGoroutine(),
	}

	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)
	m.HeapAllocBytes = memStats.HeapAlloc
	m.HeapObjects = memStats.HeapObjects
	m.RSSBytes = estimateRSS()
	m.OpenFDs = countOpenFDs()

	if stats := getNodeStats(n); stats != nil {
		m.SentMessages = stats.Sent
		m.RecvMessages = stats.Recv
		m.Duplicates = stats.Duplicates
		m.OutboxSize = stats.Outbox
		m.Panics = stats.Panics
		m.RaceWarnings = stats.Races
	}

	return m
}

func estimateRSS() int64 {
	if runtime.GOOS != "linux" {
		return 0
	}
	data, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				var kb int64
				fmt.Sscanf(fields[1], "%d", &kb)
				return kb * 1024
			}
		}
	}
	return 0
}

func countOpenFDs() int {
	if runtime.GOOS != "linux" {
		return 0
	}
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return 0
	}
	return len(entries)
}

type NodeStats struct {
	Sent       int64
	Recv       int64
	Duplicates int64
	Outbox     int64
	Panics     int
	Races      int
}

func getNodeStats(n *Node) *NodeStats {
	if n == nil {
		return nil
	}
	return &NodeStats{
		Sent:       n.sentCount.Load(),
		Recv:       n.recvCount.Load(),
		Duplicates: n.dupCount.Load(),
		Outbox:     0,
		Panics:     0,
		Races:      0,
	}
}

// GenerateFinalReport generates and persists JSON and markdown reports.
func (r *MetricsReporter) GenerateFinalReport() (*SoakReport, error) {
	r.mu.Lock()
	history := make([]MetricsSnapshot, len(r.history))
	copy(history, r.history)
	r.mu.Unlock()

	endTime := time.Now()
	duration := endTime.Sub(r.startTime)

	report := &SoakReport{
		StartTime: r.startTime,
		EndTime:   endTime,
		Duration:  duration,
		History:   history,
		PassFail:  make(map[string]bool),
		Failures:  make([]string, 0),
	}

	if len(history) > 0 {
		report.InitialSnap = history[0]
		report.FinalSnap = history[len(history)-1]
		report.NodeCount = len(report.InitialSnap.Nodes)
	}

	report.Summary = r.computeSummary(history)
	report.PassFail, report.Failures = r.evaluate(report)

	if err := r.writeJSON(report); err != nil {
		return nil, fmt.Errorf("write JSON: %w", err)
	}

	if err := r.writeMarkdown(report); err != nil {
		return nil, fmt.Errorf("write markdown: %w", err)
	}

	return report, nil
}

func (r *MetricsReporter) computeSummary(history []MetricsSnapshot) ReportSummary {
	if len(history) == 0 {
		return ReportSummary{}
	}

	var maxRSS int64
	var sumRSS int64
	var maxGoroutines int
	var maxFDs int
	var totalPanics, totalRaces int

	for _, snap := range history {
		if snap.TotalRSS > maxRSS {
			maxRSS = snap.TotalRSS
		}
		sumRSS += snap.TotalRSS
		if snap.TotalGoroutines > maxGoroutines {
			maxGoroutines = snap.TotalGoroutines
		}
		for _, n := range snap.Nodes {
			if n.OpenFDs > maxFDs {
				maxFDs = n.OpenFDs
			}
			totalPanics += n.Panics
			totalRaces += n.RaceWarnings
		}
	}

	first := history[0]
	last := history[len(history)-1]
	hours := last.Timestamp.Sub(first.Timestamp).Hours()
	if hours <= 0 {
		hours = 1.0 / 3600.0 // prevent division by zero for sub-second runs
	}

	growthRSS := float64(last.TotalRSS-first.TotalRSS) / (1024 * 1024) / hours
	growthGor := 0.0
	if first.TotalGoroutines > 0 {
		growthGor = float64(last.TotalGoroutines-first.TotalGoroutines) /
			float64(first.TotalGoroutines) * 100 / hours
	}
	growthFD := 0
	if hours > 0 {
		growthFD = int(float64(last.TotalFDs()-first.TotalFDs()) / hours)
	}

	msgLost := last.TotalSent - last.TotalRecv
	if msgLost < 0 {
		msgLost = 0
	}

	return ReportSummary{
		MaxRSSBytes:        maxRSS,
		AvgRSSBytes:        sumRSS / int64(len(history)),
		RSSGrowthRateMBpH:  growthRSS,
		MaxGoroutines:      maxGoroutines,
		GoroutineGrowthPct: growthGor,
		MaxFDs:             maxFDs,
		FDGrowth:           growthFD,
		MessagesSent:       last.TotalSent,
		MessagesRecv:       last.TotalRecv,
		MessagesLost:       msgLost,
		MessagesDuplicated: last.TotalDups,
		TotalPanics:        totalPanics,
		TotalRaces:         totalRaces,
	}
}

func (s MetricsSnapshot) TotalFDs() int {
	total := 0
	for _, n := range s.Nodes {
		total += n.OpenFDs
	}
	return total
}

func (r *MetricsReporter) evaluate(report *SoakReport) (map[string]bool, []string) {
	pf := map[string]bool{
		"rss_growth":       true,
		"goroutine_growth": true,
		"fd_growth":        true,
		"message_loss":     true,
		"message_dup":      true,
		"panics":           true,
		"races":            true,
	}
	failures := make([]string, 0)

	s := report.Summary

	if s.RSSGrowthRateMBpH > RSSGrowthLimitMBpH {
		pf["rss_growth"] = false
		failures = append(failures, fmt.Sprintf(
			"RSS growth %.2f MB/h > limit %.2f MB/h",
			s.RSSGrowthRateMBpH, RSSGrowthLimitMBpH))
	}

	if s.GoroutineGrowthPct > GoroutineGrowthLimitPct {
		pf["goroutine_growth"] = false
		failures = append(failures, fmt.Sprintf(
			"goroutine growth %.2f%%/h > limit %.2f%%/h",
			s.GoroutineGrowthPct, GoroutineGrowthLimitPct))
	}

	if s.FDGrowth > FDGrowthLimitPerHour {
		pf["fd_growth"] = false
		failures = append(failures, fmt.Sprintf(
			"FD growth %d/h > limit %d/h",
			s.FDGrowth, FDGrowthLimitPerHour))
	}

	if s.MessagesLost > MaxMessageLoss {
		pf["message_loss"] = false
		failures = append(failures, fmt.Sprintf(
			"message loss %d > limit %d",
			s.MessagesLost, MaxMessageLoss))
	}

	if s.MessagesDuplicated > MaxMessageDuplication {
		pf["message_dup"] = false
		failures = append(failures, fmt.Sprintf(
			"message duplication %d > limit %d",
			s.MessagesDuplicated, MaxMessageDuplication))
	}

	if s.TotalPanics > MaxPanics {
		pf["panics"] = false
		failures = append(failures, fmt.Sprintf(
			"panics %d > limit %d",
			s.TotalPanics, MaxPanics))
	}

	if s.TotalRaces > MaxRaces {
		pf["races"] = false
		failures = append(failures, fmt.Sprintf(
			"race warnings %d > limit %d",
			s.TotalRaces, MaxRaces))
	}

	return pf, failures
}

func (r *MetricsReporter) writeJSON(report *SoakReport) error {
	f, err := os.Create(r.reportPath)
	if err != nil {
		return err
	}
	defer f.Close()

	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	return enc.Encode(report)
}

func (r *MetricsReporter) writeMarkdown(report *SoakReport) error {
	mdPath := strings.TrimSuffix(r.reportPath, ".json") + ".md"
	f, err := os.Create(mdPath)
	if err != nil {
		return err
	}
	defer f.Close()

	fmt.Fprintf(f, "# Soak Test Report\n\n")
	fmt.Fprintf(f, "**Start:** %s\n", report.StartTime.Format(time.RFC3339))
	fmt.Fprintf(f, "**End:** %s\n", report.EndTime.Format(time.RFC3339))
	fmt.Fprintf(f, "**Duration:** %s\n", report.Duration.Round(time.Second))
	fmt.Fprintf(f, "**Nodes:** %d\n\n", report.NodeCount)

	fmt.Fprintf(f, "## Verdict: %s\n\n", verdict(report.PassFail))

	if len(report.Failures) > 0 {
		fmt.Fprintf(f, "### Failures\n")
		for _, fail := range report.Failures {
			fmt.Fprintf(f, "- ❌ %s\n", fail)
		}
		fmt.Fprintf(f, "\n")
	}

	fmt.Fprintf(f, "## Summary\n\n")
	fmt.Fprintf(f, "| Metric | Value | Limit | Status |\n")
	fmt.Fprintf(f, "|--------|-------|-------|--------|\n")

	s := report.Summary
	printRow := func(name string, val interface{}, limit interface{}, pass bool) {
		status := "✅"
		if !pass {
			status = "❌"
		}
		fmt.Fprintf(f, "| %s | %v | %v | %s |\n", name, val, limit, status)
	}

	printRow("RSS growth (MB/h)",
		fmt.Sprintf("%.2f", s.RSSGrowthRateMBpH),
		RSSGrowthLimitMBpH,
		report.PassFail["rss_growth"])

	printRow("Goroutine growth (%/h)",
		fmt.Sprintf("%.2f", s.GoroutineGrowthPct),
		GoroutineGrowthLimitPct,
		report.PassFail["goroutine_growth"])

	printRow("FD growth (/h)",
		s.FDGrowth,
		FDGrowthLimitPerHour,
		report.PassFail["fd_growth"])

	printRow("Message loss",
		s.MessagesLost,
		MaxMessageLoss,
		report.PassFail["message_loss"])

	printRow("Message duplication",
		s.MessagesDuplicated,
		MaxMessageDuplication,
		report.PassFail["message_dup"])

	printRow("Panics",
		s.TotalPanics,
		MaxPanics,
		report.PassFail["panics"])

	printRow("Races",
		s.TotalRaces,
		MaxRaces,
		report.PassFail["races"])

	fmt.Fprintf(f, "\n## Totals\n\n")
	fmt.Fprintf(f, "- Messages sent: **%d**\n", s.MessagesSent)
	fmt.Fprintf(f, "- Messages received: **%d**\n", s.MessagesRecv)
	fmt.Fprintf(f, "- Max RSS: **%.2f MB**\n", float64(s.MaxRSSBytes)/(1024*1024))
	fmt.Fprintf(f, "- Max goroutines: **%d**\n", s.MaxGoroutines)
	fmt.Fprintf(f, "- Max FDs: **%d**\n", s.MaxFDs)

	fmt.Fprintf(f, "\n## Snapshots (every 10s)\n\n")
	fmt.Fprintf(f, "| Time | RSS (MB) | Heap (MB) | Gor | Sent | Recv | Outbox |\n")
	fmt.Fprintf(f, "|------|----------|-----------|-----|------|------|--------|\n")

	for i, snap := range report.History {
		if i%6 != 0 && i != len(report.History)-1 {
			continue
		}
		t := snap.Timestamp.Sub(report.StartTime).Round(time.Second)
		fmt.Fprintf(f, "| %s | %d | %d | %d | %d | %d | %d |\n",
			t,
			snap.TotalRSS/(1024*1024),
			snap.TotalHeap/(1024*1024),
			snap.TotalGoroutines,
			snap.TotalSent,
			snap.TotalRecv,
			snap.TotalOutbox,
		)
	}

	return nil
}

func verdict(pf map[string]bool) string {
	for _, v := range pf {
		if !v {
			return "❌ FAIL"
		}
	}
	return "✅ PASS"
}
