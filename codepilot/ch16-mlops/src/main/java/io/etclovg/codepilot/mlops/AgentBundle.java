package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Bundle 数据结构管理器。
 * <p>对应书中 Ch16 §16.1 —— Agent 版本管理与分发。
 * <p>Agent Bundle 是 Agent 的完整打包描述，包含：
 * <ul>
 *   <li>版本号与元数据</li>
 *   <li>模型配置（主模型、路由策略、成本预算）</li>
 *   <li>工具清单（工具 ID、版本、权限）</li>
 *   <li>评估报告（测试通过率、性能指标）</li>
 *   <li>七层 Advisor 链配置</li>
 * </ul>
 */
@Component
public class AgentBundle {

    private static final Logger log = LoggerFactory.getLogger(AgentBundle.class);

    /**
     * Bundle 元数据。
     */
    public record BundleMetadata(
            String versionId,
            String agentId,
            String agentName,
            String description,
            String author,
            Instant createdAt,
            List<String> tags
    ) {
        public static BundleMetadata of(String versionId, String agentId, String agentName) {
            return new BundleMetadata(
                    versionId, agentId, agentName,
                    "", "system", Instant.now(), Collections.emptyList()
            );
        }

        public BundleMetadata withTags(List<String> newTags) {
            return new BundleMetadata(versionId(), agentId(), agentName(),
                    description(), author(), createdAt(), newTags);
        }
    }

    /**
     * 模型配置。
     */
    public record ModelConfig(
            String primaryModel,
            List<String> fallbackModels,
            String routerStrategy,
            double costBudgetPerRequest,
            int maxTokensPerRequest,
            Map<String, Object> modelSpecificSettings
    ) {
        public static ModelConfig defaultConfig() {
            return new ModelConfig(
                    "opus-latest",
                    List.of("sonnet-latest", "haiku-latest"),
                    "cost_based",
                    0.1,
                    8192,
                    Map.of()
            );
        }

        public ModelConfig withPrimaryModel(String newModel) {
            return new ModelConfig(newModel, fallbackModels(), routerStrategy(),
                    costBudgetPerRequest(), maxTokensPerRequest(), modelSpecificSettings());
        }
    }

    /**
     * 工具定义。
     */
    public record ToolDefinition(
            String toolId,
            String toolName,
            String toolVersion,
            String description,
            List<String> permissions,
            boolean critical
    ) {
        public static ToolDefinition of(String id, String name, String version) {
            return new ToolDefinition(id, name, version, "", Collections.emptyList(), false);
        }

        public ToolDefinition withPermissions(List<String> newPermissions) {
            return new ToolDefinition(toolId(), toolName(), toolVersion(),
                    description(), newPermissions, critical());
        }

        public ToolDefinition asCritical() {
            return new ToolDefinition(toolId(), toolName(), toolVersion(),
                    description(), permissions(), true);
        }
    }

    /**
     * 评估报告。
     */
    public record EvaluationReport(
            String reportId,
            Instant evaluatedAt,
            int totalTestCases,
            int passedTestCases,
            double passRate,
            double avgLatencyMs,
            double avgCostPerRequest,
            Map<String, Double> metricScores,
            List<String> regressions
    ) {
        public static EvaluationReport of(int total, int passed, double latency, double cost) {
            double passRate = total > 0 ? (double) passed / total : 0;
            return new EvaluationReport(
                    "eval-" + UUID.randomUUID().toString().substring(0, 8),
                    Instant.now(), total, passed, passRate, latency, cost,
                    Map.of("accuracy", passRate * 100, "efficiency", Math.max(0, 100 - latency / 10)),
                    Collections.emptyList()
            );
        }

        public boolean isPassing(double threshold) {
            return passRate >= threshold;
        }
    }

    /**
     * Advisor 链配置。
     */
    public record AdvisorChainConfig(
            List<String> enabledAdvisors,
            Map<String, Integer> advisorOrder,
            Map<String, Map<String, Object>> advisorSettings
    ) {
        public static AdvisorChainConfig defaultConfig() {
            return new AdvisorChainConfig(
                    List.of(
                            "SandboxAdvisor", "ToolValidationAdvisor", "ToolGuardAdvisor",
                            "InputGuardAdvisor", "OutputGuardAdvisor", "ObservabilityAdvisor",
                            "CostAttributionAdvisor", "SessionMonitor"
                    ),
                    Map.of(
                            "InputGuardAdvisor", 10,
                            "ToolValidationAdvisor", 20,
                            "ToolGuardAdvisor", 30,
                            "SandboxAdvisor", 40,
                            "ObservabilityAdvisor", 50,
                            "CostAttributionAdvisor", 60,
                            "OutputGuardAdvisor", 70,
                            "SessionMonitor", 80
                    ),
                    Map.of()
            );
        }
    }

    /**
     * Agent Bundle 完整结构。
     */
    public record AgentBundleStructure(
            BundleMetadata metadata,
            ModelConfig modelConfig,
            List<ToolDefinition> tools,
            EvaluationReport evaluationReport,
            AdvisorChainConfig advisorChainConfig,
            Map<String, Object> config
    ) {
        public boolean isReadyForRelease() {
            return evaluationReport != null
                    && evaluationReport.isPassing(0.9)
                    && !tools.isEmpty();
        }
    }

    private final Map<String, AgentBundleStructure> bundles = new ConcurrentHashMap<>();
    private final Map<String, List<AgentBundleStructure>> agentHistory = new ConcurrentHashMap<>();

    /**
     * 创建新的 Agent Bundle。
     */
    public AgentBundleStructure createBundle(
            String versionId, String agentId, String agentName,
            ModelConfig modelConfig, List<ToolDefinition> tools
    ) {
        log.info("[AgentBundle] ========== 开始创建新 Bundle ==========");
        log.info("[AgentBundle] 传入参数: versionId={}, agentId={}, agentName={}, toolCount={}, primaryModel={}",
                versionId, agentId, agentName, tools.size(), modelConfig.primaryModel());

        // 校验必填参数
        if (versionId == null || versionId.isBlank()) {
            log.error("[AgentBundle] 创建 Bundle 失败: versionId 为空");
            throw new IllegalArgumentException("versionId 不能为空");
        }
        if (agentId == null || agentId.isBlank()) {
            log.error("[AgentBundle] 创建 Bundle 失败: agentId 为空, versionId={}", versionId);
            throw new IllegalArgumentException("agentId 不能为空");
        }
        if (bundles.containsKey(versionId)) {
            log.warn("[AgentBundle] Bundle 已存在，将被覆盖: versionId={}, existingAgentId={}",
                    versionId, bundles.get(versionId).metadata().agentId());
        }

        BundleMetadata metadata = BundleMetadata.of(versionId, agentId, agentName);
        log.info("[AgentBundle] 创建元数据: metadata={}", metadata);

        AdvisorChainConfig advisorConfig = AdvisorChainConfig.defaultConfig();
        log.info("[AgentBundle] 加载默认 Advisor 链: enabled={}, orderCount={}",
                advisorConfig.enabledAdvisors().size(), advisorConfig.advisorOrder().size());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", versionId);
        config.put("agentId", agentId);
        config.put("createdAt", Instant.now().toString());

        AgentBundleStructure bundle = new AgentBundleStructure(
                metadata, modelConfig, tools, null, advisorConfig, config
        );

        bundles.put(versionId, bundle);
        agentHistory.computeIfAbsent(agentId, k -> Collections.synchronizedList(new ArrayList<>())).add(bundle);

        log.info("[AgentBundle] ========== Bundle 创建完成 ==========");
        log.info("[AgentBundle] 版本统计: versionId={}, tools={}, model={}, totalBundles={}, agentHistorySize={}",
                versionId, tools.size(), modelConfig.primaryModel(), bundles.size(), agentHistory.get(agentId).size());

        return bundle;
    }

    /**
     * 绑定评估报告到 Bundle。
     */
    public AgentBundleStructure attachEvaluationReport(String versionId, EvaluationReport report) {
        log.info("[AgentBundle] 准备绑定评估报告: versionId={}, reportId={}, passRate={}",
                versionId, report.reportId(), report.passRate());

        AgentBundleStructure existing = bundles.get(versionId);
        if (existing == null) {
            log.error("[AgentBundle] 绑定评估报告失败: Bundle 不存在, versionId={}", versionId);
            log.error("[AgentBundle] 当前已注册的版本: {}", bundles.keySet());
            throw new IllegalArgumentException("Bundle not found: " + versionId);
        }

        if (existing.evaluationReport() != null) {
            log.warn("[AgentBundle] 评估报告将被覆盖: versionId={}, oldReportId={}, newReportId={}",
                    versionId, existing.evaluationReport().reportId(), report.reportId());
        }

        // 检查评估报告是否达标
        if (!report.isPassing(0.9)) {
            log.warn("[AgentBundle] 评估报告未达发布标准(0.9): versionId={}, passRate={}, totalTests={}, failedTests={}",
                    versionId, report.passRate(), report.totalTestCases(),
                    report.totalTestCases() - report.passedTestCases());
        }

        AgentBundleStructure updated = new AgentBundleStructure(
                existing.metadata(), existing.modelConfig(), existing.tools(),
                report, existing.advisorChainConfig(), existing.config()
        );

        bundles.put(versionId, updated);

        log.info("[AgentBundle] ========== 评估报告绑定完成 ==========");
        log.info("[AgentBundle] 报告详情: versionId={}, passRate={}, totalTests={}, latency={}ms, cost={}",
                versionId, report.passRate(), report.totalTestCases(),
                report.avgLatencyMs(), report.avgCostPerRequest());
        log.info("[AgentBundle] 发布就绪状态: versionId={}, readyForRelease={}",
                versionId, updated.isReadyForRelease());

        return updated;
    }

    /**
     * 更新模型配置。
     */
    public AgentBundleStructure updateModelConfig(String versionId, ModelConfig newConfig) {
        log.info("[AgentBundle] 准备更新模型配置: versionId={}, oldModel={}, newModel={}",
                versionId,
                bundles.containsKey(versionId) ? bundles.get(versionId).modelConfig().primaryModel() : "N/A",
                newConfig.primaryModel());

        AgentBundleStructure existing = bundles.get(versionId);
        if (existing == null) {
            log.error("[AgentBundle] 更新模型配置失败: Bundle 不存在, versionId={}", versionId);
            log.error("[AgentBundle] 当前已注册的版本: {}", bundles.keySet());
            throw new IllegalArgumentException("Bundle not found: " + versionId);
        }

        // 检查成本预算变化
        double oldBudget = existing.modelConfig().costBudgetPerRequest();
        double newBudget = newConfig.costBudgetPerRequest();
        if (Double.compare(oldBudget, newBudget) != 0) {
            log.warn("[AgentBundle] 成本预算变更: versionId={}, oldBudget={}, newBudget={}, delta={}",
                    versionId, oldBudget, newBudget, newBudget - oldBudget);
        }

        AgentBundleStructure updated = new AgentBundleStructure(
                existing.metadata(), newConfig, existing.tools(),
                existing.evaluationReport(), existing.advisorChainConfig(), existing.config()
        );

        bundles.put(versionId, updated);

        log.info("[AgentBundle] 模型配置更新完成: versionId={}, primaryModel={}, fallbackModels={}, routerStrategy={}",
                versionId, newConfig.primaryModel(), newConfig.fallbackModels(), newConfig.routerStrategy());

        return updated;
    }

    /**
     * 添加工具到 Bundle。
     */
    public AgentBundleStructure addTool(String versionId, ToolDefinition tool) {
        log.info("[AgentBundle] 准备添加工具: versionId={}, toolId={}, toolName={}, critical={}",
                versionId, tool.toolId(), tool.toolName(), tool.critical());

        AgentBundleStructure existing = bundles.get(versionId);
        if (existing == null) {
            log.error("[AgentBundle] 添加工具失败: Bundle 不存在, versionId={}", versionId);
            log.error("[AgentBundle] 当前已注册的版本: {}", bundles.keySet());
            throw new IllegalArgumentException("Bundle not found: " + versionId);
        }

        // 检查工具是否重复
        boolean toolExists = existing.tools().stream()
                .anyMatch(t -> t.toolId().equals(tool.toolId()));
        if (toolExists) {
            log.warn("[AgentBundle] 工具已存在，将被替换: versionId={}, toolId={}", versionId, tool.toolId());
        }

        List<ToolDefinition> updatedTools = new ArrayList<>(existing.tools());
        // 替换已存在的工具或添加新工具
        boolean replaced = false;
        for (int i = 0; i < updatedTools.size(); i++) {
            if (updatedTools.get(i).toolId().equals(tool.toolId())) {
                updatedTools.set(i, tool);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            updatedTools.add(tool);
        }

        AgentBundleStructure updated = new AgentBundleStructure(
                existing.metadata(), existing.modelConfig(), updatedTools,
                existing.evaluationReport(), existing.advisorChainConfig(), existing.config()
        );

        bundles.put(versionId, updated);

        log.info("[AgentBundle] 工具操作完成: versionId={}, toolId={}, action={}, totalTools={}",
                versionId, tool.toolId(), replaced ? "REPLACED" : "ADDED", updatedTools.size());

        // 如果添加后工具列表为空，警告
        if (updatedTools.isEmpty()) {
            log.warn("[AgentBundle] 警告: 工具列表为空: versionId={}", versionId);
        }

        return updated;
    }

    /**
     * 根据版本号获取 Bundle。
     */
    public Optional<AgentBundleStructure> getBundle(String versionId) {
        Optional<AgentBundleStructure> result = Optional.ofNullable(bundles.get(versionId));
        if (result.isPresent()) {
            log.debug("[AgentBundle] 获取 Bundle 成功: versionId={}, agentId={}",
                    versionId, result.get().metadata().agentId());
        } else {
            log.warn("[AgentBundle] Bundle 不存在: versionId={}, available={}", versionId, bundles.keySet());
        }
        return result;
    }

    /**
     * 获取 Agent 的所有历史版本。
     */
    public List<AgentBundleStructure> getAgentHistory(String agentId) {
        List<AgentBundleStructure> history = agentHistory.getOrDefault(agentId, Collections.emptyList());
        log.debug("[AgentBundle] 获取 Agent 历史版本: agentId={}, versionCount={}", agentId, history.size());
        if (history.isEmpty()) {
            log.warn("[AgentBundle] Agent 无历史版本: agentId={}", agentId);
        }
        return Collections.unmodifiableList(history);
    }

    /**
     * 获取指定 Agent 的最新版本。
     */
    public Optional<AgentBundleStructure> getLatestVersion(String agentId) {
        List<AgentBundleStructure> history = agentHistory.getOrDefault(agentId, Collections.emptyList());
        if (history.isEmpty()) {
            log.warn("[AgentBundle] Agent 无版本历史: agentId={}", agentId);
            return Optional.empty();
        }
        AgentBundleStructure latest = history.get(history.size() - 1);
        log.debug("[AgentBundle] 获取最新版本: agentId={}, versionId={}",
                agentId, latest.metadata().versionId());
        return Optional.of(latest);
    }

    /**
     * 检查 Bundle 是否准备好发布。
     */
    public boolean isReadyForRelease(String versionId) {
        AgentBundleStructure bundle = bundles.get(versionId);
        if (bundle == null) {
            log.warn("[AgentBundle] 检查发布状态失败: Bundle 不存在, versionId={}", versionId);
            return false;
        }

        boolean hasTools = !bundle.tools().isEmpty();
        boolean hasReport = bundle.evaluationReport() != null;
        boolean passRateOk = hasReport && bundle.evaluationReport().isPassing(0.9);
        boolean ready = hasTools && hasReport && passRateOk;

        if (!ready) {
            log.warn("[AgentBundle] Bundle 未就绪发布: versionId={}, hasTools={}, hasReport={}, passRateOk={}",
                    versionId, hasTools, hasReport, passRateOk);
        } else {
            log.info("[AgentBundle] Bundle 就绪发布: versionId={}, tools={}, passRate={}",
                    versionId, bundle.tools().size(), bundle.evaluationReport().passRate());
        }

        return ready;
    }

    /**
     * 删除 Bundle（仅用于测试或清理）。
     */
    public void deleteBundle(String versionId) {
        log.info("[AgentBundle] 准备删除 Bundle: versionId={}", versionId);

        AgentBundleStructure removed = bundles.remove(versionId);
        if (removed == null) {
            log.warn("[AgentBundle] 删除失败: Bundle 不存在, versionId={}", versionId);
            return;
        }

        String agentId = removed.metadata().agentId();
        List<AgentBundleStructure> history = agentHistory.get(agentId);
        int removedFromHistory = 0;
        if (history != null) {
            int initialSize = history.size();
            history.removeIf(b -> b.metadata().versionId().equals(versionId));
            removedFromHistory = initialSize - history.size();
        }

        log.info("[AgentBundle] Bundle 删除完成: versionId={}, agentId={}, removedFromHistory={}, remainingBundles={}",
                versionId, agentId, removedFromHistory, bundles.size());
    }

    /**
     * 获取所有 Bundle 的统计信息。
     */
    public Map<String, Object> getStatistics() {
        log.debug("[AgentBundle] 生成统计信息");

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalBundles", bundles.size());
        stats.put("totalAgents", agentHistory.size());

        long readyCount = bundles.values().stream()
                .filter(AgentBundleStructure::isReadyForRelease)
                .count();
        stats.put("readyForRelease", readyCount);
        stats.put("notReady", bundles.size() - readyCount);

        Map<String, Integer> toolsPerModel = new LinkedHashMap<>();
        for (AgentBundleStructure bundle : bundles.values()) {
            String model = bundle.modelConfig().primaryModel();
            toolsPerModel.merge(model, bundle.tools().size(), Integer::sum);
        }
        stats.put("toolsPerModel", toolsPerModel);

        Map<String, Integer> modelDistribution = new LinkedHashMap<>();
        for (AgentBundleStructure bundle : bundles.values()) {
            String model = bundle.modelConfig().primaryModel();
            modelDistribution.merge(model, 1, Integer::sum);
        }
        stats.put("modelDistribution", modelDistribution);

        log.debug("[AgentBundle] 统计信息: totalBundles={}, readyForRelease={}, totalAgents={}",
                bundles.size(), readyCount, agentHistory.size());

        return stats;
    }
}
