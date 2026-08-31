package io.etclovg.codepilot.foundation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 报告工具集：为 Agent 提供执行报告生成能力
 * 对应书中 Ch01 §1.3 —— Agent 结果汇报机制
 */
@Component
public class ReportTools {

    private static final Logger log = LoggerFactory.getLogger(ReportTools.class);

    private final Map<String, Report> reports = new ConcurrentHashMap<>();

    public Report generateReport(String taskId, String title, String content,
                                  ReportFormat format) {
        log.info("[ReportTools] 生成报告: taskId={}, title={}, format={}",
                taskId, title, format);

        String reportId = "rpt-" + UUID.randomUUID().toString().substring(0, 8);
        Report report = new Report(
                reportId, taskId, title, content, format,
                Instant.now(), ReportStatus.GENERATED
        );

        reports.put(reportId, report);
        return report;
    }

    public String formatReport(Report report, ReportFormat format) {
        return switch (format) {
            case MARKDOWN -> formatMarkdown(report);
            case PLAIN_TEXT -> formatPlainText(report);
            case BULLET_LIST -> formatBulletList(report);
            case JSON -> formatJson(report);
        };
    }

    public Optional<Report> getReport(String reportId) {
        return Optional.ofNullable(reports.get(reportId));
    }

    public List<Report> getReportsByTask(String taskId) {
        return reports.values().stream()
                .filter(r -> r.taskId().equals(taskId))
                .sorted(Comparator.comparing(Report::generatedAt).reversed())
                .toList();
    }

    public List<Report> getAllReports() {
        return reports.values().stream()
                .sorted(Comparator.comparing(Report::generatedAt).reversed())
                .toList();
    }

    public boolean deleteReport(String reportId) {
        Report removed = reports.remove(reportId);
        if (removed != null) {
            log.info("[ReportTools] 报告已删除: reportId={}", reportId);
            return true;
        }
        return false;
    }

    private String formatMarkdown(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(report.title()).append("\n\n");
        sb.append("**Task ID**: ").append(report.taskId()).append("\n\n");
        sb.append("**Generated At**: ").append(report.generatedAt()).append("\n\n");
        sb.append("---\n\n");
        sb.append(report.content()).append("\n");
        return sb.toString();
    }

    private String formatPlainText(Report report) {
        return String.format("报告标题: %s%n任务ID: %s%n生成时间: %s%n---%n%s",
                report.title(), report.taskId(), report.generatedAt(), report.content());
    }

    private String formatBulletList(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 标题: ").append(report.title()).append("\n");
        sb.append("- 任务ID: ").append(report.taskId()).append("\n");
        sb.append("- 生成时间: ").append(report.generatedAt()).append("\n");
        sb.append("- 内容:\n");
        String[] lines = report.content().split("\n");
        for (String line : lines) {
            sb.append("  - ").append(line).append("\n");
        }
        return sb.toString();
    }

    private String formatJson(Report report) {
        return String.format("""
                {
                  "reportId": "%s",
                  "taskId": "%s",
                  "title": "%s",
                  "content": "%s",
                  "format": "%s",
                  "generatedAt": "%s",
                  "status": "%s"
                }
                """,
                report.reportId(), report.taskId(),
                escapeJson(report.title()), escapeJson(report.content()),
                report.format(), report.generatedAt(), report.status());
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public enum ReportFormat {
        MARKDOWN, PLAIN_TEXT, BULLET_LIST, JSON
    }

    public enum ReportStatus {
        GENERATED, PUBLISHED, ARCHIVED
    }

    public record Report(
            String reportId, String taskId, String title,
            String content, ReportFormat format,
            Instant generatedAt, ReportStatus status
    ) {}
}