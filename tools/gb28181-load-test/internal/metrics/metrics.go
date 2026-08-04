package metrics

import (
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
)

type histogram struct { count uint64; sumBits uint64; maxBits uint64 }
type Registry struct { mu sync.RWMutex; counters map[string]*uint64; gauges map[string]*int64; histograms map[string]*histogram }
func NewRegistry() *Registry { return &Registry{counters: make(map[string]*uint64), gauges: make(map[string]*int64), histograms: make(map[string]*histogram)} }
func (r *Registry) Counter(name string, delta uint64) { r.mu.Lock(); p := r.counters[name]; if p == nil { var v uint64; p = &v; r.counters[name] = p }; r.mu.Unlock(); atomic.AddUint64(p, delta) }
func (r *Registry) Gauge(name string, value int64) { r.mu.Lock(); p := r.gauges[name]; if p == nil { var v int64; p = &v; r.gauges[name] = p }; r.mu.Unlock(); atomic.StoreInt64(p, value) }
func (r *Registry) Observe(name string, value float64) { r.mu.Lock(); h := r.histograms[name]; if h == nil { h = &histogram{}; r.histograms[name] = h }; r.mu.Unlock(); atomic.AddUint64(&h.count, 1); atomicAddFloat(&h.sumBits, value); for { old := atomic.LoadUint64(&h.maxBits); if value <= floatFromBits(old) || atomic.CompareAndSwapUint64(&h.maxBits, old, floatBits(value)) { break } } }
func (r *Registry) Snapshot() map[string]float64 { out := map[string]float64{}; r.mu.RLock(); defer r.mu.RUnlock(); for n, p := range r.counters { out[n] = float64(atomic.LoadUint64(p)) }; for n, p := range r.gauges { out[n] = float64(atomic.LoadInt64(p)) }; for n, h := range r.histograms { out[n+"_count"] = float64(atomic.LoadUint64(&h.count)); out[n+"_sum"] = floatFromBits(atomic.LoadUint64(&h.sumBits)); out[n+"_max"] = floatFromBits(atomic.LoadUint64(&h.maxBits)) }; return out }
func (r *Registry) ServeHTTP(w http.ResponseWriter, _ *http.Request) { s := r.Snapshot(); keys := make([]string, 0, len(s)); for k := range s { keys = append(keys, k) }; sort.Strings(keys); w.Header().Set("Content-Type", "text/plain; version=0.0.4"); for _, k := range keys { _, _ = fmt.Fprintf(w, "%s %s\n", k, strconv.FormatFloat(s[k], 'f', -1, 64)) } }

func floatBits(value float64) uint64 { return math.Float64bits(value) }
func floatFromBits(value uint64) float64 { return math.Float64frombits(value) }
func atomicAddFloat(target *uint64, delta float64) { for { old := atomic.LoadUint64(target); next := floatFromBits(old) + delta; if atomic.CompareAndSwapUint64(target, old, floatBits(next)) { return } } }
