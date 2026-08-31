package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * E2B 云沙箱实战演示——对应书中 §4.2.4 3 人创业团队 MVP 场景。
 * <p>展示一个完整流程：
 * <ol>
 *   <li>在 e2b.dev 控制台完成 3 项**额外工作**（注册 → API Key → 创建 Python+Playwright Template → 设预算告警）</li>
 *   <li>配置 Spring 注入 E2BSandboxClientOptions（API Key 走环境变量 E2B_API_KEY，避免提交到 git）</li>
 *   <li>用户上传爬虫代码 → writeFile 进沙箱 → exec 执行 → readFile 拿回结果 CSV</li>
 *   <li>finally 销毁沙箱（重要：不关就是按秒一直扣费，同时 maxLifetime 兜底）</li>
 * </ol>
 */
@Component
public class E2BSandboxDemo {

    private static final Logger log = LoggerFactory.getLogger(E2BSandboxDemo.class);
    private final E2BSandboxClient e2b;

    public E2BSandboxDemo(E2BSandboxClient e2b) {
        this.e2b = e2b;
    }

    /**
     * 书中 §4.2.4 的 3 人创业团队 MVP 示例：用户上传 Python 爬虫代码，
     * E2B 启动一个 Firecracker microVM（底层）执行，按秒计费。
     */
    public void demonstrateScraperMvp(String userScriptBytes, String apiKeyFromEnv) {
        // ================== 先做 3 项额外工作（E2B 后端独有的前置条件） ==================
        // 1) 已在 e2b.dev 注册并获取 API Key（通过环境变量 E2B_API_KEY 传入，不能写死在代码里）
        // 2) 已在 E2B 控制台创建 Template "tmpl_python_pw_v3"，预装 Python 3.11 + Playwright + requests
        //    ——不做这步：每次冷启动都 pip install，浪费 30 秒计费时间（$0.001）
        // 3) 已在控制台设月度预算上限 $200 + 用量达 80% 邮件告警（避免 runaway Agent 刷爆账单）

        E2BSandboxClientOptions opts = new E2BSandboxClientOptions()
            .apiKey(apiKeyFromEnv)                                  // 🔑 API Key：环境变量注入
            .templateId("tmpl_python_pw_v3")                       // 📦 Template：预安装好 Python+Playwright
            .region("ap-northeast-1")                              // 🌏 区域：东京（延迟最低）
            .cpuCores(2).memoryMb(2048)                            // ⚙️ 规格：2核 2GB（爬虫需要内存）
            .maxLifetimeSeconds(600)                               // 🛑 兜底：10 分钟强制销毁（防死循环扣费）
            .destroyOnIdle(true).idleTimeoutSeconds(60)            // 🛑 第二层兜底：空闲 60s 销毁
            .allowInternetAccess(true)                             // 🌐 爬虫需要联网
            .timeoutSeconds(120);

        String sandboxId = null;
        double totalCost = 0.0;
        try {
            // ① 创建沙箱（Firecracker 启动 ~1-3s，开记秒）
            sandboxId = e2b.createSandbox(opts);

            // ② 上传用户的爬虫脚本（Agent 接收用户上传 → 写入沙箱文件系统）
            e2b.writeFile(sandboxId, "/home/user/scraper.py",
                    userScriptBytes == null ? new byte[0] : userScriptBytes.getBytes());

            // ③ 执行（Python 代码爬 10 个页面 → 输出 results.csv）
            var result = e2b.exec(sandboxId, opts, "python /home/user/scraper.py --pages 10");
            totalCost += result.estimatedCostUsd();
            log.info("[Demo] 爬取完成，本次 exec 费用: ${}", String.format("%.5f", result.estimatedCostUsd()));

            // ④ 读取结果 CSV 返回给用户
            byte[] csv = e2b.readFile(sandboxId, "/home/user/results.csv");
            log.info("[Demo] 结果文件 {} bytes", csv.length);
        } finally {
            // ⚠️ 必须销毁！否则按 maxLifetime 才停，600 秒的话多花 $0.02
            if (sandboxId != null) {
                e2b.destroySandbox(sandboxId);
                log.info("[Demo] 沙箱已销毁，本轮任务累计估算费用: ${}", String.format("%.5f", totalCost));
            }
        }
    }
}
