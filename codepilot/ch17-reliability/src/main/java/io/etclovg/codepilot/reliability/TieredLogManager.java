package io.etclovg.codepilot.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 生产监控层 · 三级日志存储管理器（热/温/冷）。
 *
 * <p>TieredLogManager 实现了分层存储的日志管理策略，
 * 借鉴数据库的冷热分离思想，将日志按时间分为三层存储：
 *
 * <ul>
 *   <li><b>热层（Hot）</b> — 最近 1 小时的日志，存储在内存中，支持快速查询
 *       对应书中 Ch17 §17.2 "热数据快速访问" 模式</li>
 *   <li><b>温层（Warm）</b> — 最近 1 天的日志，存储在 SQLite/文件系统中
 *       平衡查询性能与存储成本</li>
 *   <li><b>冷层（Cold）</b> — 更久的日志，压缩后归档存储
 *       对应书中 Ch17 §17.2 "冷数据归档" 模式</li>
 * </ul>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>自动层级迁移：热层 → 温层 → 冷层</li>
 *   <li>按时间范围和关键词查询日志</li>
 *   <li>提供日志统计信息（总量、级别分布、来源分布）</li>
 *   <li>压缩归档冷层日志，节省存储空间</li>
 * </ul>
 *
 * <p>对应书中 Ch17 §17.2 可观测性分层。
 */
@Component
public class TieredLogManager {

    private static final Logger log = LoggerFactory.getLogger(TieredLogManager.class);

    /** 热层保留时长（秒）：1 小时 */
    private static final long HOT_RETENTION_SECONDS = 3600L;

    /** 温层保留时长（秒）：1 天 */
    private static final long WARM_RETENTION_SECONDS = 86400L;

    /** 迁移检查间隔（秒）：5 分钟 */
    private static final long MIGRATION_INTERVAL_SECONDS = 300L;

    /** 压缩阈值：压缩冷层日志的大小（压缩后数据大小限制） */
    private static final int COMPRESSION_THRESHOLD_BYTES = 1024 * 1024;

    /** 热层：内存日志缓存，使用 ConcurrentHashMap 保证线程安全 */
    private final Map<String, LogEntry> hotLayer = new ConcurrentHashMap<>();

    /** 热层日志 ID 计数器 */
    private final AtomicLong hotIdCounter = new AtomicLong(0);

    /** 温层：文件系统日志存储（每小时一个文件） */
    private Path warmLogDir = Path.of(System.getProperty("user.home"), ".codepilot", "logs", "warm");

    /** 冷层：压缩归档日志存储 */
    private Path coldLogDir = Path.of(System.getProperty("user.home"), ".codepilot", "logs", "cold");

    /** 日志统计信息 */
    private final LogStatistics statistics = new LogStatistics();

    /** 定时迁移调度器 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tiered-log-migration");
        t.setDaemon(true);
        return t;
    });

    /** 层级配置 */
    public enum LogTier {
        /** 热层：内存缓存，最近 1 小时 */
        HOT,
        /** 温层：文件系统，最近 1 天 */
        WARM,
        /** 冷层：压缩归档，1 天以上 */
        COLD
    }

    /** 日志级别枚举 */
    public enum LogLevel {
        TRACE, DEBUG, INFO, WARN, ERROR
    }

    public TieredLogManager() {
        // 初始化目录
        try {
            Files.createDirectories(warmLogDir);
            Files.createDirectories(coldLogDir);
        } catch (IOException e) {
            log.error("[分层日志] 日志目录初始化失败: {}", e.getMessage());
        }

        // 启动定时迁移任务
        scheduler.scheduleAtFixedRate(this::performMigration,
                MIGRATION_INTERVAL_SECONDS, MIGRATION_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("[分层日志] TieredLogManager 初始化完成: hotLayer={}, warmDir={}, coldDir={}",
                "memory", warmLogDir, coldLogDir);
    }

    // ========== 写入接口 ==========

    /**
     * 记录一条日志，自动路由到对应层级
     */
    public void log(String source, LogLevel level, String message, Map<String, String> metadata) {
        LogEntry entry = new LogEntry(
                "log-" + hotIdCounter.incrementAndGet(),
                Instant.now(),
                level,
                source,
                message,
                metadata != null ? metadata : Map.of()
        );

        // 写入热层（所有新日志先进热层）
        hotLayer.put(entry.id(), entry);
        statistics.recordEntry(level);

        // 日志量控制：热层超过 100000 条时触发立即迁移
        if (hotLayer.size() > 100_000) {
            log.warn("[分层日志] 热层日志数量超过 100000，触发紧急迁移");
            performHotToWarmMigration();
        }

        // 详细日志打印
        if (level == LogLevel.ERROR || level == LogLevel.WARN) {
            log.warn("[分层日志] [{}] {} - {}", level, source, message);
        } else {
            log.debug("[分层日志] [{}] {} - {}", level, source, message);
        }
    }

    /**
     * 便捷方法：记录 INFO 级别日志
     */
    public void info(String source, String message) {
        log(source, LogLevel.INFO, message, null);
    }

    /**
     * 便捷方法：记录 WARN 级别日志
     */
    public void warn(String source, String message) {
        log(source, LogLevel.WARN, message, null);
    }

    /**
     * 便捷方法：记录 ERROR 级别日志
     */
    public void error(String source, String message, Map<String, String> metadata) {
        log(source, LogLevel.ERROR, message, metadata);
    }

    // ========== 查询接口 ==========

    /**
     * 按时间范围查询日志（从热层和温层查询）
     */
    public List<LogEntry> queryByTimeRange(Instant startTime, Instant endTime) {
        log.info("[分层日志] 查询时间范围: {} → {}", startTime, endTime);
        List<LogEntry> results = new ArrayList<>();

        // 从热层查询
        hotLayer.values().stream()
                .filter(e -> !e.timestamp().isBefore(startTime) && !e.timestamp().isAfter(endTime))
                .forEach(results::add);

        // 从温层文件查询
        try {
            List<LogEntry> warmEntries = readWarmLogsByTimeRange(startTime, endTime);
            results.addAll(warmEntries);
        } catch (IOException e) {
            log.error("[分层日志] 温层日志读取失败: {}", e.getMessage());
        }

        // 从冷层查询（仅在时间范围超出温层保留期时）
        if (startTime.isBefore(Instant.now().minusSeconds(WARM_RETENTION_SECONDS))) {
            try {
                List<LogEntry> coldEntries = readColdLogsByTimeRange(startTime, endTime);
                results.addAll(coldEntries);
            } catch (IOException e) {
                log.error("[分层日志] 冷层日志读取失败: {}", e.getMessage());
            }
        }

        // 按时间排序
        results.sort(Comparator.comparing(LogEntry::timestamp));

        log.info("[分层日志] 查询完成: 找到 {} 条日志", results.size());
        return results;
    }

    /**
     * 按关键词查询日志
     */
    public List<LogEntry> queryByKeyword(String keyword) {
        log.info("[分层日志] 关键词查询: {}", keyword);
        List<LogEntry> results = new ArrayList<>();

        String lowerKeyword = keyword.toLowerCase();

        // 从热层查询
        hotLayer.values().stream()
                .filter(e -> e.message().toLowerCase().contains(lowerKeyword)
                        || e.source().toLowerCase().contains(lowerKeyword))
                .forEach(results::add);

        // 从温层查询
        try {
            readAllWarmLogs().stream()
                    .filter(e -> e.message().toLowerCase().contains(lowerKeyword)
                            || e.source().toLowerCase().contains(lowerKeyword))
                    .forEach(results::add);
        } catch (IOException e) {
            log.error("[分层日志] 温层关键词查询失败: {}", e.getMessage());
        }

        log.info("[分层日志] 关键词查询完成: 找到 {} 条匹配日志", results.size());
        return results;
    }

    /**
     * 按日志级别查询
     */
    public List<LogEntry> queryByLevel(LogLevel level) {
        List<LogEntry> results = new ArrayList<>();

        hotLayer.values().stream()
                .filter(e -> e.level() == level)
                .forEach(results::add);

        try {
            readAllWarmLogs().stream()
                    .filter(e -> e.level() == level)
                    .forEach(results::add);
        } catch (IOException e) {
            log.error("[分层日志] 级别查询失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 获取最近 N 条热层日志
     */
    public List<LogEntry> getRecentHotLogs(int limit) {
        return hotLayer.values().stream()
                .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    // ========== 统计接口 ==========

    /**
     * 获取日志统计信息
     */
    public LogStatistics.LogStats getStatistics() {
        return statistics.getStats();
    }

    /**
     * 获取各层级日志数量
     */
    public Map<String, Long> getTierCounts() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("hot", (long) hotLayer.size());
        try {
            counts.put("warm", countWarmLogs());
        } catch (IOException e) {
            counts.put("warm", 0L);
        }
        try {
            counts.put("cold", countColdLogs());
        } catch (IOException e) {
            counts.put("cold", 0L);
        }
        return counts;
    }

    // ========== 层级迁移 ==========

    /**
     * 执行层级迁移（热 → 温 → 冷）
     */
    private void performMigration() {
        try {
            log.info("[分层日志] 开始层级迁移...");

            // 热 → 温：迁移超过 1 小时的热层日志
            performHotToWarmMigration();

            // 温 → 冷：迁移超过 1 天的温层日志
            performWarmToColdMigration();

            log.info("[分层日志] 层级迁移完成");
        } catch (Exception e) {
            log.error("[分层日志] 层级迁移异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 热层 → 温层迁移
     */
    private void performHotToWarmMigration() {
        Instant cutoff = Instant.now().minusSeconds(HOT_RETENTION_SECONDS);
        List<LogEntry> toMigrate = hotLayer.values().stream()
                .filter(e -> e.timestamp().isBefore(cutoff))
                .toList();

        if (toMigrate.isEmpty()) {
            return;
        }

        log.info("[分层日志] 热→温迁移: {} 条日志", toMigrate.size());

        // 按小时分组写入温层
        Map<Path, List<LogEntry>> groupedByHour = groupWarmEntriesByHour(toMigrate);

        groupedByHour.forEach((filePath, entries) -> {
            try {
                appendEntriesToWarmFile(filePath, entries);
                // 从热层移除已迁移的日志
                entries.forEach(e -> hotLayer.remove(e.id()));
            } catch (IOException e) {
                log.error("[分层日志] 写入温层文件失败: {}", filePath, e);
            }
        });

        log.info("[分层日志] 热→温迁移完成: 剩余热层 {} 条", hotLayer.size());
    }

    /**
     * 温层 → 冷层迁移
     */
    private void performWarmToColdMigration() {
        Instant cutoff = Instant.now().minusSeconds(WARM_RETENTION_SECONDS);

        try {
            List<Path> oldWarmFiles = findOldWarmFiles(cutoff);
            for (Path filePath : oldWarmFiles) {
                try {
                    // 读取旧温层文件
                    List<LogEntry> entries = readWarmFile(filePath);

                    // 压缩并写入冷层
                    Path coldFile = compressAndArchive(filePath, entries);

                    // 删除原温层文件
                    Files.delete(filePath);
                    log.info("[分层日志] 温→冷迁移: {} → {}", filePath.getFileName(), coldFile.getFileName());
                } catch (IOException e) {
                    log.error("[分层日志] 温→冷迁移失败: {}", filePath, e);
                }
            }
        } catch (IOException e) {
            log.error("[分层日志] 查找过期温层文件失败: {}", e.getMessage());
        }
    }

    // ========== 温层文件操作 ==========

    /**
     * 按小时分组温层条目
     */
    private Map<Path, List<LogEntry>> groupWarmEntriesByHour(List<LogEntry> entries) {
        Map<Path, List<LogEntry>> grouped = new HashMap<>();

        for (LogEntry entry : entries) {
            Instant hourKey = entry.timestamp().truncatedTo(ChronoUnit.HOURS);
            String fileName = "warm-" + hourKey.toString().replace(":", "-") + ".jsonl";
            Path filePath = warmLogDir.resolve(fileName);

            grouped.computeIfAbsent(filePath, k -> new ArrayList<>()).add(entry);
        }

        return grouped;
    }

    /**
     * 追加条目到温层文件
     */
    private void appendEntriesToWarmFile(Path filePath, List<LogEntry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append(toJsonLine(entry)).append("\n");
        }
        Files.writeString(filePath, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 读取单个温层文件
     */
    private List<LogEntry> readWarmFile(Path filePath) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        if (!Files.exists(filePath)) return entries;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    entries.add(fromJsonLine(line));
                } catch (Exception e) {
                    log.warn("[分层日志] 解析温层日志行失败: {}", line.substring(0, Math.min(100, line.length())));
                }
            }
        }
        return entries;
    }

    /**
     * 读取所有温层日志
     */
    private List<LogEntry> readAllWarmLogs() throws IOException {
        List<LogEntry> all = new ArrayList<>();
        if (!Files.exists(warmLogDir)) return all;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(warmLogDir, "warm-*.jsonl")) {
            for (Path file : stream) {
                all.addAll(readWarmFile(file));
            }
        }
        return all;
    }

    /**
     * 按时间范围读取温层日志
     */
    private List<LogEntry> readWarmLogsByTimeRange(Instant start, Instant end) throws IOException {
        return readAllWarmLogs().stream()
                .filter(e -> !e.timestamp().isBefore(start) && !e.timestamp().isAfter(end))
                .toList();
    }

    /**
     * 查找过期的温层文件
     */
    private List<Path> findOldWarmFiles(Instant cutoff) throws IOException {
        List<Path> oldFiles = new ArrayList<>();
        if (!Files.exists(warmLogDir)) return oldFiles;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(warmLogDir, "warm-*.jsonl")) {
            for (Path file : stream) {
                // 从文件名解析时间戳
                String fileName = file.getFileName().toString();
                try {
                    // 文件名格式: warm-2024-01-01T00-00-00Z.jsonl
                    String timeStr = fileName.substring(5, fileName.length() - 6)
                            .replaceFirst("-", ":")
                            .replaceFirst("-", ":")
                            .replaceFirst("-", ":")
                            .replaceFirst("-", ":")
                            .replaceFirst("-", ":");
                    Instant fileTime = Instant.parse(timeStr);
                    if (fileTime.isBefore(cutoff)) {
                        oldFiles.add(file);
                    }
                } catch (Exception e) {
                    log.debug("[分层日志] 无法解析文件名时间: {}", fileName);
                }
            }
        }
        return oldFiles;
    }

    // ========== 冷层操作 ==========

    /**
     * 压缩并归档到冷层
     */
    private Path compressAndArchive(Path sourceFile, List<LogEntry> entries) throws IOException {
        String archiveName = "cold-" + Instant.now().toString().replace(":", "-") + ".gz";
        Path archivePath = coldLogDir.resolve(archiveName);

        // 构建 JSONL 内容
        StringBuilder sb = new StringBuilder();
        for (LogEntry entry : entries) {
            sb.append(toJsonLine(entry)).append("\n");
        }
        byte[] rawData = sb.toString().getBytes(StandardCharsets.UTF_8);

        // 使用 DEFLATE 压缩
        byte[] compressed = compress(rawData);

        Files.write(archivePath, compressed);
        double compressionRatio = (1 - (double) compressed.length / rawData.length) * 100;
        log.info("[分层日志] 冷层归档: {} 字节 → {} 字节, 压缩率 {}%",
                rawData.length, compressed.length,
                String.format("%.1f", compressionRatio));

        return archivePath;
    }

    /**
     * 读取冷层日志
     */
    private List<LogEntry> readColdLogsByTimeRange(Instant start, Instant end) throws IOException {
        List<LogEntry> results = new ArrayList<>();
        if (!Files.exists(coldLogDir)) return results;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(coldLogDir, "cold-*.gz")) {
            for (Path file : stream) {
                try {
                    byte[] compressed = Files.readAllBytes(file);
                    byte[] decompressed = decompress(compressed);
                    String jsonl = new String(decompressed, StandardCharsets.UTF_8);

                    for (String line : jsonl.split("\n")) {
                        if (!line.isBlank()) {
                            try {
                                LogEntry entry = fromJsonLine(line);
                                if (!entry.timestamp().isBefore(start) && !entry.timestamp().isAfter(end)) {
                                    results.add(entry);
                                }
                            } catch (Exception e) {
                                log.debug("[分层日志] 解析冷层日志行失败");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[分层日志] 读取冷层归档失败: {}", file.getFileName());
                }
            }
        }
        return results;
    }

    /**
     * 压缩数据
     */
    private byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[COMPRESSION_THRESHOLD_BYTES];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            outputStream.write(buffer, 0, count);
        }
        deflater.end();
        return outputStream.toByteArray();
    }

    /**
     * 解压数据
     */
    private byte[] decompress(byte[] compressedData) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedData.length);
        byte[] buffer = new byte[COMPRESSION_THRESHOLD_BYTES];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
        } catch (Exception e) {
            inflater.end();
            throw new IOException("解压数据失败", e);
        }
        inflater.end();
        return outputStream.toByteArray();
    }

    // ========== 计数方法 ==========

    private long countWarmLogs() throws IOException {
        return readAllWarmLogs().size();
    }

    private long countColdLogs() throws IOException {
        long count = 0;
        if (!Files.exists(coldLogDir)) return 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(coldLogDir, "cold-*.gz")) {
            for (Path file : stream) {
                try {
                    byte[] compressed = Files.readAllBytes(file);
                    byte[] decompressed = decompress(compressed);
                    String jsonl = new String(decompressed, StandardCharsets.UTF_8);
                    count += jsonl.split("\n").length;
                } catch (Exception e) {
                    log.debug("[分层日志] 计数冷层文件失败: {}", file.getFileName());
                }
            }
        }
        return count;
    }

    // ========== JSON 序列化 ==========

    private String toJsonLine(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(entry.id()).append("\"");
        sb.append(",\"timestamp\":\"").append(entry.timestamp()).append("\"");
        sb.append(",\"level\":\"").append(entry.level()).append("\"");
        sb.append(",\"source\":\"").append(escapeJson(entry.source())).append("\"");
        sb.append(",\"message\":\"").append(escapeJson(entry.message())).append("\"");
        sb.append(",\"metadata\":{");

        if (entry.metadata() != null && !entry.metadata().isEmpty()) {
            sb.append(entry.metadata().entrySet().stream()
                    .map(e -> "\"" + escapeJson(e.getKey()) + "\":\"" + escapeJson(e.getValue()) + "\"")
                    .collect(Collectors.joining(",")));
        }

        sb.append("}}");
        return sb.toString();
    }

    private LogEntry fromJsonLine(String line) {
        // 简单的 JSON 解析（避免引入额外依赖）
        String id = extractJsonString(line, "id");
        Instant timestamp = Instant.parse(extractJsonString(line, "timestamp"));
        LogLevel level = LogLevel.valueOf(extractJsonString(line, "level"));
        String source = extractJsonString(line, "source");
        String message = extractJsonString(line, "message");
        Map<String, String> metadata = extractJsonMap(line, "metadata");

        return new LogEntry(id, timestamp, level, source, message, metadata);
    }

    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return "";
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return unescapeJson(json.substring(start, end));
    }

    private Map<String, String> extractJsonMap(String json, String key) {
        Map<String, String> result = new HashMap<>();
        String searchKey = "\"" + key + "\":{";
        int start = json.indexOf(searchKey);
        if (start == -1) return result;

        int braceStart = json.indexOf("{", start);
        int braceEnd = findMatchingBrace(json, braceStart);
        if (braceEnd == -1) return result;

        String inner = json.substring(braceStart + 1, braceEnd);
        // 简单解析 key-value 对
        String[] pairs = inner.split(",");
        for (String pair : pairs) {
            int colonIdx = pair.indexOf("\":\"");
            if (colonIdx > 0) {
                String k = unescapeJson(pair.substring(1, colonIdx));
                String v = unescapeJson(pair.substring(colonIdx + 3, pair.length() - 1));
                result.put(k, v);
            }
        }
        return result;
    }

    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // ========== 数据模型 ==========

    /**
     * 日志条目数据模型
     * 对应书中 Ch17 §17.2 LogEntry 数据结构
     */
    public record LogEntry(
            String id,
            Instant timestamp,
            LogLevel level,
            String source,
            String message,
            Map<String, String> metadata
    ) {}

    /**
     * 日志统计信息
     */
    public static class LogStatistics {
        private final AtomicLong totalEntries = new AtomicLong(0);
        private final Map<LogLevel, AtomicLong> levelCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> sourceCounts = new ConcurrentHashMap<>();

        public LogStatistics() {
            for (LogLevel level : LogLevel.values()) {
                levelCounts.put(level, new AtomicLong(0));
            }
        }

        void recordEntry(LogLevel level) {
            totalEntries.incrementAndGet();
            levelCounts.get(level).incrementAndGet();
        }

        public LogStats getStats() {
            Map<String, Long> levelDist = new HashMap<>();
            levelCounts.forEach((k, v) -> levelDist.put(k.name(), v.get()));

            return new LogStats(totalEntries.get(), levelDist);
        }

        public record LogStats(long totalEntries, Map<String, Long> levelDistribution) {}
    }

    // ========== 生命周期 ==========

    /**
     * 关闭管理器（在应用关闭时调用）
     */
    public void shutdown() {
        scheduler.shutdown();

        // 执行最终迁移
        try {
            performMigration();
        } catch (Exception e) {
            log.error("[分层日志] 关闭前迁移失败: {}", e.getMessage());
        }

        log.info("[分层日志] TieredLogManager 已关闭");
    }

    // ========== 配置方法 ==========

    public void setWarmLogDir(Path warmLogDir) {
        this.warmLogDir = warmLogDir;
    }

    public void setColdLogDir(Path coldLogDir) {
        this.coldLogDir = coldLogDir;
    }
}