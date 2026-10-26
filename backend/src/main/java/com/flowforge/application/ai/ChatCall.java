package com.flowforge.application.ai;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * How one attempt must be performed. The provider never sees credentials, only the header the node derived from them.
 *
 * @param auth          header carrying the secret, if the node uses a credential
 * @param deadlineNanos {@link System#nanoTime()} value by which the attempt must be over (the node's whole budget)
 * @param cancelled     true once the execution was cancelled; the provider aborts the request
 */
public record ChatCall(Optional<Header> auth, long deadlineNanos, BooleanSupplier cancelled) {

    public record Header(String name, String value) {

        @Override
        public String toString() {
            return "Header[name=" + name + ", value=<hidden>]";
        }
    }
}
