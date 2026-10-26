package com.flowforge.application.security;

/**
 * Who is acting. The single-user MVP has exactly one owner ({@link #LOCAL_OWNER}) and no authentication: the trust
 * boundary is the localhost-only binding (see {@code docs/ARCHITECTURE.md}, "Trust boundary").
 *
 * <p>Future multi-user or workspace support replaces the implementation (e.g. from the authenticated principal) and
 * keeps the contract: every owned resource stores an owner id, and services compare it with
 * {@link #currentOwnerId()} before returning or using the resource. Background work (schedules, webhooks) acts as the
 * owner of the workflow, never as "the current user".
 */
public interface OwnerContext {

    String LOCAL_OWNER = "local";

    /** Owner id of the current request; never null. */
    String currentOwnerId();
}
