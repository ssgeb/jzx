package com.ruanzhu.doorhandlecatch.service.impl;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.ruanzhu.doorhandlecatch.service.ModelValidationService;
import com.ruanzhu.doorhandlecatch.util.OnnxImageDetectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class OnnxModelValidationService implements ModelValidationService {

    @Override
    public ValidationResult validate(Path modelPath) {
        if (modelPath == null || !Files.exists(modelPath)) {
            return new ValidationResult("FAILED", "模型文件不存在");
        }

        try (OrtSession session = OnnxImageDetectionUtil.loadModel(modelPath.toString())) {
            int inputCount = session.getInputNames().size();
            int outputCount = session.getOutputNames().size();
            if (inputCount == 0 || outputCount == 0) {
                return new ValidationResult("FAILED", "ONNX 模型缺少输入或输出节点");
            }
            return new ValidationResult(
                    "PASSED",
                    "ONNX 加载成功，输入节点 " + inputCount + " 个，输出节点 " + outputCount + " 个"
            );
        } catch (OrtException | RuntimeException e) {
            log.warn("ONNX 模型校验失败: {}", modelPath, e);
            return new ValidationResult("FAILED", "ONNX 加载失败: " + e.getMessage());
        }
    }
}
