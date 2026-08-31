package io.etclovg.codepilot.data;

/**
 * 训练样本对。
 * <p>对应书中 Ch13 §13.3 —— 数据飞轮清洗阶段产出的训练数据单元。
 * <p>由 {@link DataCleaner#clean} 对 {@link ErrorSample} 去重、归一化、
 * 自动标注后产出，供 {@link ModelTrainer#incrementalFineTune} 增量微调使用。
 *
 * @param input  输入文本
 * @param output 期望输出文本
 */
public record TrainingPair(String input, String output) {
}
