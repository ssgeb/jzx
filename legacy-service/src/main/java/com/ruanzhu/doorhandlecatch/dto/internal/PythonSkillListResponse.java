package com.ruanzhu.doorhandlecatch.dto.internal;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PythonSkillListResponse {
    private boolean enabled;
    private List<PythonSkillRecord> skills = new ArrayList<>();
}
