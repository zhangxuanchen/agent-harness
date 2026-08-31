package io.etclovg.codepilot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱工具——Agent 可调用的沙箱执行入口。
 * <p>对应书中 Ch04 §4.1.1 & §4.2.1 —— 提供进程级与容器级两种沙箱执行方式。
 * <p>{@code executeInSandbox} 使用 Docker 容器隔离（生产环境），
 * {@code executeInProcess} 使用 {@link ProcessBuilder} 子进程（开发环境，不可用于生产）。
 * 两类方法均声明为 AgentScope {@code @Tool}，供 Agent 在工具调用阶段执行。
 */
@Component
public class SandboxTool {

    private static final Logger log = LoggerFactory.getLogger(SandboxTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    // ===== 生产环境：Docker 容器隔离 =====

    /**
     * 在 Docker 沙箱中执行 Shell 命令并返回输出。
     * <p>对应书中 §4.1.1 的 executeInSandbox——Docker 命名空间 + cgroup 硬约束。
     * <p>加固参数：完全断网、非 root、cap-drop=ALL、只读根文件系统、seccomp、资源限制。
     *
     * @param command 要执行的 Shell 命令
     * @return 命令输出，超时返回错误信息
     */
    // @Tool(description = "在隔离沙箱中执行 Shell 命令并返回输出")
    public String executeInSandbox(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "run", "--rm",
            "--network=none",              // 断网
            "--memory=512m",               // 内存限制
            "--cpus=1",                    // CPU 限制
            "--read-only",                 // 根文件系统只读
            "--cap-drop=ALL",              // 丢弃所有 capability
            "--security-opt=no-new-privileges", // 禁止特权提升
            "-v", "sandbox-workspace:/workspace",  // Docker 命名卷，隔离于宿主
            "sandbox-image:latest",       // 预构建的最小化镜像
            "sh", "-c", command
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("[SandboxTool] 命令执行超时（{}s），已强制终止: {}", DEFAULT_TIMEOUT_SECONDS, command);
            return "ERROR: 命令执行超时（" + DEFAULT_TIMEOUT_SECONDS + "s），已强制终止";
        }
        return new String(process.getInputStream().readAllBytes());
    }

    // ===== 开发环境：进程级执行（⚠️ 不可用于生产） =====

    /**
     * 在指定工作目录中执行 Shell 命令（开发环境用，不可用于生产）。
     * <p>对应书中 §4.2.1 的 executeInProcess——仅限制工作目录和超时，
     * 不提供任何命名空间隔离，子进程可访问宿主全部文件系统和网络。
     *
     * @param command 要执行的 Shell 命令
     * @param workDir 工作目录
     * @return 命令输出，超时返回错误信息
     */
    // @Tool(description = "在指定工作目录中执行 Shell 命令（开发环境用，不可用于生产）")
    public String executeInProcess(String command, String workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            log.warn("[SandboxTool] 进程超时（{}s），已强制终止: {}", DEFAULT_TIMEOUT_SECONDS, command);
            return "ERROR: 超时终止";
        }
        String output = new String(process.getInputStream().readAllBytes());
        return process.exitValue() == 0 ? output : "ERROR (exit " + process.exitValue() + "): " + output;
    }
}