package dev.cimiento.core.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Monday to Friday calendar without holidays; contract terms in calendar months. */
public final class WorkCalendar {

    private WorkCalendar() {
    }

    /** Date of 0-based working day {@code index}; {@code start} must be a Monday. */
    public static LocalDate workday(LocalDate start, int index) {
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("start must be a Monday: " + start);
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        return start.plusDays(7L * (index / 5) + index % 5);
    }

    /** Last calendar day allowed by a term of {@code months} starting on {@code start}. */
    public static LocalDate deadline(LocalDate start, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        return start.plusMonths(months).minusDays(1);
    }
}
