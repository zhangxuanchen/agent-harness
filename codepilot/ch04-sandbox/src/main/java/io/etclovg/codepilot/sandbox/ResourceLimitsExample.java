package io.etclovg.codepilot.sandbox;

/**
 * ResourceLimits record 定义与实例化完整示例。
 * <p>对应书中 Ch04 §4.5.4 —— Cgroups 硬隔离的资源配额声明。
 * <p>
 * 本示例展示：
 * 1. 如何定义一个 record
 * 2. 如何通过不同方式实例化 record
 * 3. record 的特性（不可变、自动生成方法等）
 * <p>
 * 运行方式：在 IDE 中直接运行 main 方法即可查看输出。
 */
public class ResourceLimitsExample {

    public static void main(String[] args) {
        // ===== 方式 1：使用预定义常量（最常用）=====
        System.out.println("=== 方式 1：使用预定义常量 ===");
        
        // 默认配置（保守：CPU 1核 / 512MB / 10GB / 30s 超时 / 断网）
        ResourceLimits defaultLimits = ResourceLimits.DEFAULT;
        System.out.println("DEFAULT: " + format(defaultLimits));
        
        // 生产环境配置（CPU 2核 / 2GB / 50GB / 60s 超时 / 100Mbps 带宽）
        ResourceLimits prodLimits = ResourceLimits.PRODUCTION;
        System.out.println("PRODUCTION: " + format(prodLimits));
        System.out.println();

        // ===== 方式 2：使用静态工厂方法 =====
        System.out.println("=== 方式 2：使用静态工厂方法 ===");
        
        // 断网配置（适合不需要网络的纯计算任务）
        ResourceLimits noNetLimits = ResourceLimits.noNetwork(1.0, 512, 10);
        System.out.println("noNetwork: " + format(noNetLimits));
        
        // 默认配置（等同于 DEFAULT）
        ResourceLimits fromDefaults = ResourceLimits.defaults();
        System.out.println("defaults(): " + format(fromDefaults));
        System.out.println();

        // ===== 方式 3：使用构造器自定义配置 =====
        System.out.println("=== 方式 3：使用构造器自定义配置 ===");
        
        // 为特定任务定制：CPU 4核 / 8GB 内存 / 100GB 磁盘 / 300 进程 / 500Mbps / 120s 超时
        ResourceLimits customLimits = new ResourceLimits(
            4.0,    // cpuCores: 4 核
            8192,   // memoryMb: 8GB
            100,    // diskLimitGb: 100GB
            300,    // processLimit: 300 个进程
            500,    // networkMbps: 500Mbps 带宽
            120     // timeoutSeconds: 120 秒超时
        );
        System.out.println("自定义配置: " + format(customLimits));
        System.out.println();

        // ===== record 特性演示 =====
        System.out.println("=== record 特性演示 ===");
        
        // 特性 1：自动生成 getter（方法名与字段名相同）
        System.out.println("特性 1：自动生成 getter");
        System.out.println("  cpuCores() → " + prodLimits.cpuCores());
        System.out.println("  memoryMb() → " + prodLimits.memoryMb() + "MB");
        System.out.println("  isNetworkDisabled() → " + prodLimits.isNetworkDisabled());
        System.out.println();
        
        // 特性 2：不可变（没有 setter，创建后无法修改）
        System.out.println("特性 2：不可变（创建后无法修改）");
        System.out.println("  ResourceLimits 没有 setter 方法，cpuCores 是 final 的");
        System.out.println("  如需修改，必须创建新实例");
        System.out.println();
        
        // 特性 3：自动生成 equals/hashCode
        System.out.println("特性 3：自动生成 equals/hashCode");
        ResourceLimits sameAsDefault = new ResourceLimits(1.0, 512, 10, 64, 0, 30);
        System.out.println("  DEFAULT.equals(sameAsDefault) → " + 
            ResourceLimits.DEFAULT.equals(sameAsDefault));  // true
        System.out.println("  DEFAULT == sameAsDefault → " + 
            (ResourceLimits.DEFAULT == sameAsDefault));  // false（不同对象）
        System.out.println();
        
        // 特性 4：自动生成 toString
        System.out.println("特性 4：自动生成 toString");
        System.out.println("  PRODUCTION.toString() → " + prodLimits.toString().substring(0, 80) + "...");
        System.out.println();

        // ===== 实际使用场景 =====
        System.out.println("=== 实际使用场景 ===");
        
        // 场景 1：选择适合的配置
        ResourceLimits limits;
        boolean isProduction = true;
        boolean needsNetwork = true;
        
        if (isProduction && needsNetwork) {
            limits = ResourceLimits.PRODUCTION;  // 生产环境 + 需要网络
        } else if (!needsNetwork) {
            limits = ResourceLimits.noNetwork(1.0, 512, 10);  // 不需要网络
        } else {
            limits = ResourceLimits.DEFAULT;  // 默认配置
        }
        System.out.println("选择的配置: " + format(limits));
        
        // 场景 2：注入 SandboxConfig（后续步骤）
        SandboxConfig config = SandboxConfig.builder()
            .isolation(SandboxIsolation.DOCKER)
            .resourceLimits(limits)
            .build();
        System.out.println("已注入 SandboxConfig，等待框架转换为 cgroups 参数...");
        System.out.println("SandboxConfig: isolation=" + config.getIsolation());
    }

    /**
     * 格式化输出 ResourceLimits。
     */
    private static String format(ResourceLimits r) {
        return String.format("[cpu=%.1f核, mem=%dMB, disk=%dGB, pids=%d, net=%dMbps, timeout=%ds]",
            r.cpuCores(), r.memoryMb(), r.diskLimitGb(), 
            r.processLimit(), r.networkMbps(), r.timeoutSeconds());
    }
}
