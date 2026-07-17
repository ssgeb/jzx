package com.ruanzhu.doorhandlecatch.security;

import java.util.Set;

public record TenantPrincipal(Long userId, Long tenantId, String username, Set<String> roles) {

    public TenantPrincipal {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
