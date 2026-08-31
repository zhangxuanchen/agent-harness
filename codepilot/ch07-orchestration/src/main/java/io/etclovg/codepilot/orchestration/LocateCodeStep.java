package io.etclovg.codepilot.orchestration;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 定位代码步骤。
 * <p>对应书中 Ch07 §7.2 —— 在代码库中定位问题相关的代码。
 */
@Component
public class LocateCodeStep implements Step {

    @Override
    public String getName() { return "LocateCode"; }

    @Override
    public StepResult execute(Map<String, Object> context) {
        return StepResult.ok("代码定位完成");
    }
}