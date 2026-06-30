package com.ruanzhu.doorhandlecatch.security;

import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.entity.DetectionTask;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionTaskAccessPolicyTest {

    private final DetectionTaskAccessPolicy policy = new DetectionTaskAccessPolicy();

    @Test
    void operatorCannotAccessForeignTask() {
        DetectionTask task = taskOwnedBy("alice");
        var auth = authentication("bob", "ROLE_OPERATOR");

        assertThatThrownBy(() -> policy.assertCanAccess(task, auth))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权访问该资源");
    }

    @Test
    void ownerCanAccessTask() {
        policy.assertCanAccess(taskOwnedBy("alice"), authentication("alice", "ROLE_OPERATOR"));
    }

    @Test
    void adminCanAccessForeignTask() {
        policy.assertCanAccess(taskOwnedBy("alice"), authentication("root", "ROLE_ADMIN"));
    }

    @Test
    void anonymousCannotAccessTask() {
        assertThatThrownBy(() -> policy.assertCanAccess(taskOwnedBy("alice"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权访问该资源");
    }

    private DetectionTask taskOwnedBy(String username) {
        DetectionTask task = new DetectionTask();
        task.setCreatedBy(username);
        return task;
    }

    private UsernamePasswordAuthenticationToken authentication(String username, String authority) {
        return new UsernamePasswordAuthenticationToken(
                username, "n/a", List.of(new SimpleGrantedAuthority(authority)));
    }
}
