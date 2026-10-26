package com.flowforge.application.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

    @Test
    void fiveFieldExpressionsRunAtSecondZero() {
        CronSchedule hourly = CronSchedule.parse("0 * * * *", "UTC");
        assertThat(hourly.next(Instant.parse("2026-03-01T10:15:30Z"))).isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
        assertThat(hourly.next(Instant.parse("2026-03-01T11:00:00Z"))).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
    }

    @Test
    void sixFieldExpressionsHaveSeconds() {
        CronSchedule everyTen = CronSchedule.parse("*/10 * * * * *", "UTC");
        assertThat(everyTen.next(Instant.parse("2026-03-01T10:00:01Z"))).isEqualTo(Instant.parse("2026-03-01T10:00:10Z"));
    }

    @Test
    void occurrencesAreComputedInTheScheduleTimeZone() {
        CronSchedule nineInBerlin = CronSchedule.parse("0 9 * * *", "Europe/Berlin");
        // Winter (UTC+1) and summer (UTC+2): 09:00 local either way.
        assertThat(nineInBerlin.next(Instant.parse("2026-01-10T00:00:00Z"))).isEqualTo(Instant.parse("2026-01-10T08:00:00Z"));
        assertThat(nineInBerlin.next(Instant.parse("2026-07-10T00:00:00Z"))).isEqualTo(Instant.parse("2026-07-10T07:00:00Z"));
    }

    @Test
    void daylightSavingTransitionsSkipNonexistentAndRepeatAmbiguousTimes() {
        // Europe/Berlin springs forward on 2026-03-29 at 02:00 (02:30 does not exist) and falls back on 2026-10-25
        // (02:30 happens twice).
        CronSchedule halfPastTwo = CronSchedule.parse("30 2 * * *", "Europe/Berlin");
        Instant spring = halfPastTwo.next(Instant.parse("2026-03-28T12:00:00Z"));
        assertThat(spring.atZone(ZoneId.of("Europe/Berlin")).toLocalDateTime()).hasToString("2026-03-30T02:30");

        Instant autumn = halfPastTwo.next(Instant.parse("2026-10-24T12:00:00Z"));
        assertThat(autumn).isEqualTo(Instant.parse("2026-10-25T00:30:00Z")); // 02:30 CEST
        assertThat(halfPastTwo.next(autumn)).isEqualTo(Instant.parse("2026-10-25T01:30:00Z")); // 02:30 CET
    }

    @Test
    void latestUpToFindsTheLastOccurrenceNotAfterNow() {
        CronSchedule everyMinute = CronSchedule.parse("* * * * *", "UTC");
        Instant from = Instant.parse("2026-03-01T10:00:00Z");
        assertThat(everyMinute.latestUpTo(from, Instant.parse("2026-03-01T10:42:30Z")))
                .isEqualTo(Instant.parse("2026-03-01T10:42:00Z"));
        assertThat(everyMinute.latestUpTo(from, from)).isEqualTo(from);
    }

    @Test
    void latestUpToIsBoundedForVeryFrequentSchedules() {
        CronSchedule everySecond = CronSchedule.parse("* * * * * *", "UTC");
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant latest = everySecond.latestUpTo(from, from.plusSeconds(10L * CronSchedule.MAX_STEPS));
        assertThat(latest).isEqualTo(from.plusSeconds(CronSchedule.MAX_STEPS));
    }

    @Test
    void invalidInputHasUserFacingMessages() {
        assertThatThrownBy(() -> CronSchedule.parse("* * *", "UTC")).hasMessageContaining("5 fields");
        assertThatThrownBy(() -> CronSchedule.parse("0 25 * * *", "UTC")).hasMessageStartingWith("is not a valid cron");
        assertThatThrownBy(() -> CronSchedule.parse("0 9 * * *", "Mars/Olympus")).hasMessageContaining("time zone");
        assertThatThrownBy(() -> CronSchedule.parse(" ", "UTC")).hasMessage("must not be empty");
    }

    @Test
    void impossibleDatesNeverFire() {
        assertThat(CronSchedule.parse("0 0 30 2 *", "UTC").next(ZonedDateTime.now().toInstant())).isNull();
    }
}
