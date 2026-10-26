package com.flowforge.application.trigger;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.scheduling.support.CronExpression;

/**
 * A parsed cron expression bound to a time zone.
 *
 * <p>Accepts the common 5-field form ({@code minute hour day-of-month month day-of-week}, interpreted with second 0) and
 * the 6-field form with a leading seconds field. Occurrences are computed in the schedule's zone, so "every day at
 * 09:00 Europe/Berlin" follows daylight saving time. On transition days (Spring's {@link CronExpression} semantics) a
 * local time that does not exist (spring forward) has no occurrence that day, and a repeated local time (fall back)
 * occurs twice, once per UTC offset (two distinct instants, so both run).
 */
public final class CronSchedule {

    /** Upper bound when walking over missed occurrences (e.g. a per-second cron after a long downtime). */
    static final int MAX_STEPS = 100_000;

    private final String expression;
    private final CronExpression cron;
    private final ZoneId zone;

    private CronSchedule(String expression, CronExpression cron, ZoneId zone) {
        this.expression = expression;
        this.cron = cron;
        this.zone = zone;
    }

    /** @throws IllegalArgumentException with a user-facing message when the expression or zone is invalid */
    public static CronSchedule parse(String expression, String timezone) {
        return new CronSchedule(expression, parseCron(expression), parseZone(timezone));
    }

    public static CronExpression parseCron(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("must not be empty");
        }
        String[] fields = expression.strip().split("\\s+");
        String normalized = switch (fields.length) {
            case 5 -> "0 " + String.join(" ", fields);
            case 6 -> String.join(" ", fields);
            default -> throw new IllegalArgumentException("must have 5 fields (minute hour day month weekday) "
                    + "or 6 fields with leading seconds");
        };
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("is not a valid cron expression (" + e.getMessage() + ")");
        }
    }

    public static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("must not be empty");
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("is not a known time zone (use an IANA name such as Europe/Berlin)");
        }
    }

    /** The first occurrence strictly after {@code after}, or null if the expression never fires again. */
    public Instant next(Instant after) {
        ZonedDateTime next = cron.next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    /**
     * The latest occurrence that is not after {@code now}, starting from a known occurrence {@code from <= now}.
     * Walks forward at most {@link #MAX_STEPS} occurrences and returns the last one reached.
     */
    public Instant latestUpTo(Instant from, Instant now) {
        Instant latest = Objects.requireNonNull(from);
        for (int i = 0; i < MAX_STEPS; i++) {
            Instant next = next(latest);
            if (next == null || next.isAfter(now)) {
                return latest;
            }
            latest = next;
        }
        return latest;
    }

    public String expression() {
        return expression;
    }

    public ZoneId zone() {
        return zone;
    }
}
