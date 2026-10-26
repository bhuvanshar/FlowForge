package com.flowforge.application.ai;

import java.math.BigDecimal;

/**
 * Usage reported by the provider; any field may be absent (null). {@code cost} is only set when the provider returns one
 * (e.g. OpenRouter's {@code usage.cost}); FlowForge never estimates prices. The JSON names avoid the word "token":
 * stored history redacts keys containing it.
 */
public record Usage(Long prompt, Long completion, Long total, BigDecimal cost) {

    public static final Usage NONE = new Usage(null, null, null, null);

    public boolean isEmpty() {
        return prompt == null && completion == null && total == null && cost == null;
    }

    /** Sum of two usages (several attempts of one node); absent + absent stays absent. */
    public Usage plus(Usage other) {
        return new Usage(add(prompt, other.prompt), add(completion, other.completion), add(total, other.total),
                cost == null ? other.cost : other.cost == null ? cost : cost.add(other.cost));
    }

    private static Long add(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Long.valueOf(a + b); // explicit: a ternary mixing Long and long would unbox (and fail on) null
    }
}
