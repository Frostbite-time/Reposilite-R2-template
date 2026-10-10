package com.frostbite.reposilite.quota;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

final class BillingPeriod {
    final Instant start, end;

    BillingPeriod(Instant start, Instant end) { this.start = start; this.end = end; }

    static BillingPeriod current(Instant now, int day, ZoneId zone) {
        YearMonth month = YearMonth.from(now.atZone(zone));
        Instant start = boundary(month, day, zone);
        if (now.isBefore(start)) { month = month.minusMonths(1); start = boundary(month, day, zone); }
        return new BillingPeriod(start, boundary(month.plusMonths(1), day, zone));
    }

    private static Instant boundary(YearMonth month, int day, ZoneId zone) {
        return month.atDay(Math.min(day, month.lengthOfMonth())).atStartOfDay(zone).toInstant();
    }
}
