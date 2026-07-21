package com.ruanzhu.doorhandlecatch.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

@Data
public class AgentSkillInstallRequest {

    @NotBlank
    @Size(max = 200)
    @Pattern(regexp = "[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})/[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})",
            message = "repository 必须使用 owner/repository 格式")
    private String repository;

    @NotBlank
    @Size(max = 500)
    private String path;

    @NotBlank
    @Size(max = 200)
    private String ref = "main";

    @AssertTrue(message = "Skill 来源路径或版本不安全")
    public boolean isSourceSafe() {
        return isSafeSourcePart(path) && isSafeSourcePart(ref);
    }

    private boolean isSafeSourcePart(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
            return false;
        }
        for (String part : value.split("/")) {
            if ("..".equals(part)) {
                return false;
            }
        }
        return value.chars().noneMatch(character -> character < 32);
    }
}
