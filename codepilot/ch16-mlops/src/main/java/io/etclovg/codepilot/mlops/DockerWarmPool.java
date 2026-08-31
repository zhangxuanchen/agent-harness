package io.etclovg.codepilot.mlops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Docker 容器暖池。
 * <p>对应书中 Ch16 §16.4 —— 评估与执行环境的容器预热。
 * <p>预创建并保持一组就绪的 Docker 容器，缩短冷启动延迟，
 * 供评估工作池与沙箱池按需取用。
 */
@Component
public class DockerWarmPool {

    private static final Logger log = LoggerFactory.getLogger(DockerWarmPool.class);

    private final ConcurrentLinkedDeque<String> warmContainers = new ConcurrentLinkedDeque<>();
    private final int targetSize;

    public DockerWarmPool() {
        this(5);
    }

    public DockerWarmPool(int targetSize) {
        this.targetSize = targetSize;
    }

    /**
     * 从暖池获取一个容器 ID。
     *
     * @return 容器 ID，池空时返回 null
     */
    public String acquire() {
        String id = warmContainers.pollFirst();
        log.debug("获取容器: id={}, remaining={}", id, warmContainers.size());
        return id;
    }

    /**
     * 归还容器到暖池。
     *
     * @param containerId 容器 ID
     */
    public void release(String containerId) {
        if (containerId != null) {
            warmContainers.addFirst(containerId);
            log.debug("归还容器: id={}, size={}", containerId, warmContainers.size());
        }
    }

    /**
     * 当前暖池大小。
     *
     * @return 容器数量
     */
    public int size() {
        return warmContainers.size();
    }

    /**
     * 目标暖池大小。
     *
     * @return 目标数量
     */
    public int targetSize() {
        return targetSize;
    }
}
