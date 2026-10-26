package com.flowforge.infrastructure.security;

import com.flowforge.application.security.OwnerContext;
import org.springframework.stereotype.Component;

/** Single-user MVP: every request acts as the local owner. */
@Component
public class LocalOwnerContext implements OwnerContext {

    @Override
    public String currentOwnerId() {
        return LOCAL_OWNER;
    }
}
