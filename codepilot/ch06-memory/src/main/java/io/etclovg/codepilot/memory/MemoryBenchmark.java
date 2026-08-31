package io.etclovg.codepilot.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆基准测试工具：用于评估记忆系统的性能
 * 对应书中 Ch06 §6.4 —— 记忆系统性能评估
 */
@Component
public class MemoryBenchmark {

    private static final Logger log = LoggerFactory.getLogger(MemoryBenchmark.class);

    private final Map<String, BenchmarkResult> results = new ConcurrentHashMap<>();

    public BenchmarkResult runWriteBenchmark(int entryCount, int entrySize) {
        log.info("[MemoryBenchmark] 开始写入基准测试: entries={}, size={}", entryCount, entrySize);

        String benchmarkId = "bench-write-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.nanoTime();

        AtomicInteger counter = new AtomicInteger(0);
        Map<String, String> testStore = new ConcurrentHashMap<>();

        for (int i = 0; i < entryCount; i++) {
            String key = "key-" + i;
            String value = "x".repeat(Math.min(entrySize, 10_000));
            testStore.put(key, value);
            counter.incrementAndGet();
        }

        long durationNs = System.nanoTime() - startTime;
        double avgLatencyMs = (double) durationNs / entryCount / 1_000_000.0;
        double throughput = entryCount / ((double) durationNs / 1_000_000_000.0);

        BenchmarkResult result = new BenchmarkResult(
                benchmarkId, "write", entryCount, entrySize,
                durationNs / 1_000_000.0, avgLatencyMs, throughput,
                testStore.size(), Instant.now()
        );

        results.put(benchmarkId, result);
        log.info("[MemoryBenchmark] 写入基准完成: {}ms, avgLatency={}ms, throughput={} ops/s",
                String.format("%.2f", durationNs / 1_000_000.0),
                String.format("%.4f", avgLatencyMs),
                String.format("%.0f", throughput));

        return result;
    }

    public BenchmarkResult runReadBenchmark(int entryCount, int readCount) {
        log.info("[MemoryBenchmark] 开始读取基准测试: entries={}, reads={}", entryCount, readCount);

        String benchmarkId = "bench-read-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.nanoTime();

        Map<String, String> testStore = new HashMap<>();
        for (int i = 0; i < entryCount; i++) {
            testStore.put("key-" + i, "value-" + i);
        }

        Random random = new Random(42);
        int found = 0;
        for (int i = 0; i < readCount; i++) {
            String key = "key-" + random.nextInt(entryCount);
            String value = testStore.get(key);
            if (value != null) found++;
        }

        long durationNs = System.nanoTime() - startTime;
        double avgLatencyMs = (double) durationNs / readCount / 1_000_000.0;
        double throughput = readCount / ((double) durationNs / 1_000_000_000.0);

        BenchmarkResult result = new BenchmarkResult(
                benchmarkId, "read", readCount, entryCount,
                durationNs / 1_000_000.0, avgLatencyMs, throughput,
                found, Instant.now()
        );

        results.put(benchmarkId, result);
        log.info("[MemoryBenchmark] 读取基准完成: {}ms, avgLatency={}ms, throughput={} ops/s",
                String.format("%.2f", durationNs / 1_000_000.0),
                String.format("%.4f", avgLatencyMs),
                String.format("%.0f", throughput));

        return result;
    }

    public BenchmarkResult runSearchBenchmark(int entryCount, int searchCount) {
        log.info("[MemoryBenchmark] 开始搜索基准测试: entries={}, searches={}", entryCount, searchCount);

        String benchmarkId = "bench-search-" + UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.nanoTime();

        List<String> testData = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            testData.add("document content for entry " + i + " with some keywords");
        }

        int matches = 0;
        for (int i = 0; i < searchCount; i++) {
            String query = "entry " + (int) (Math.random() * entryCount);
            for (String doc : testData) {
                if (doc.contains(query)) matches++;
            }
        }

        long durationNs = System.nanoTime() - startTime;
        double avgLatencyMs = (double) durationNs / searchCount / 1_000_000.0;

        BenchmarkResult result = new BenchmarkResult(
                benchmarkId, "search", searchCount, entryCount,
                durationNs / 1_000_000.0, avgLatencyMs,
                matches / ((double) durationNs / 1_000_000_000.0),
                matches, Instant.now()
        );

        results.put(benchmarkId, result);
        return result;
    }

    public List<BenchmarkResult> getAllResults() {
        return results.values().stream()
                .sorted(Comparator.comparing(BenchmarkResult::timestamp).reversed())
                .toList();
    }

    public Optional<BenchmarkResult> getResult(String benchmarkId) {
        return Optional.ofNullable(results.get(benchmarkId));
    }

    public void clear() {
        int size = results.size();
        results.clear();
        log.info("[MemoryBenchmark] 清除基准结果: count={}", size);
    }

    public record BenchmarkResult(
            String id, String operation, int operationCount,
            int dataSize, double totalDurationMs,
            double avgLatencyMs, double throughput,
            long itemCount, Instant timestamp
    ) {}
}