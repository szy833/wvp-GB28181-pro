package metrics

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)

type Registry struct { mu sync.RWMutex; counters map[string]*uint64; gauges map[string]*int64 }
func NewRegistry() *Registry { return &Registry{counters: make(map[string]*uint64), gauges: make(map[string]*int64)} }
func (r *Registry) Counter(name string, delta uint64) { r.mu.Lock(); p := r.counters[name]; if p == nil { var v uint64; p = &v; r.counters[name] = p }; r.mu.Unlock(); atomic.AddUint64(p, delta) }
func (r *Registry) Gauge(name string, value int64) { r.mu.Lock(); p := r.gauges[name]; if p == nil { var v int64; p = &v; r.gauges[name] = p }; r.mu.Unlock(); atomic.StoreInt64(p, value) }
func (r *Registry) Snapshot() map[string]float64 { out := map[string]float64{}; r.mu.RLock(); defer r.mu.RUnlock(); for n, p := range r.counters { out[n] = float64(atomic.LoadUint64(p)) }; for n, p := range r.gauges { out[n] = float64(atomic.LoadInt64(p)) }; return out }
func (r *Registry) ServeHTTP(w http.ResponseWriter, _ *http.Request) { s := r.Snapshot(); keys := make([]string, 0, len(s)); for k := range s { keys = append(keys, k) }; sort.Strings(keys); w.Header().Set("Content-Type", "text/plain; version=0.0.4"); for _, k := range keys { _, _ = fmt.Fprintf(w, "%s %s\n", k, strconv.FormatFloat(s[k], 'f', -1, 64)) } }
