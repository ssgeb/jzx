package com.ruanzhu.doorhandlecatch.security;

import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.entity.DetectionTask;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class DetectionTaskAccessPolicy {

    public boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority != null
                        && "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    public void assertCanAccess(DetectionTask task, Authentication authentication) {
        boolean owner = task != null
                && authentication != null
                && authentication.getName() != null
                && authentication.getName().equals(task.getCreatedBy());
        if (!isAdmin(authentication) && !owner) {
            throw new BusinessException(403, "无权访问该资源");
        }
    }
}
