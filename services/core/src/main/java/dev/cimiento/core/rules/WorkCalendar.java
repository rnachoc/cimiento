package dev.cimiento.core.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Working days of a work: Monday to Friday, minus the holidays of its calendar. Contract terms are in
 * calendar months and progress weeks are calendar weeks, so neither depends on holidays.
 *
 * <p>{@link #STANDARD} has no holidays and is what every work uses unless a person chooses otherwise;
 * the generated ground truth is built on it.
 */
public final class WorkCalendar {

    /** Which public holidays the calendar takes out. */
    public enum Kind { NONE, ES_NATIONAL }

    /** Monday to Friday without holidays. */
    public static final WorkCalendar STANDARD = new WorkCalendar(Kind.NONE, Map.of());

    private final Kind kind;
    private final TreeMap<LocalDate, String> local;

    /** A calendar of the given kind plus regional or local holidays (date to name). */
    public WorkCalendar(Kind kind, Map<LocalDate, String> local) {
        this.kind = kind;
        this.local = new TreeMap<>(local);
    }

    public Kind kind() {
        return kind;
    }

    private boolean standard() {
        return kind == Kind.NONE && local.isEmpty();
    }

    /** Name of the holiday on that date, local first; empty when it is not a holiday. */
    public Optional<String> holiday(LocalDate date) {
        String name = local.get(date);
        if (name != null) {
            return Optional.of(name);
        }
        if (kind == Kind.ES_NATIONAL) {
            return Holidays.spainNational(date.getYear()).stream().filter(h -> h.date().equals(date))
                    .map(Holidays.Holiday::name).findFirst();
        }
        return Optional.empty();
    }

    public boolean isWorkingDay(LocalDate date) {
        DayOfWeek d = date.getDayOfWeek();
        return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && holiday(date).isEmpty();
    }

    /** Date of 0-based working day {@code index} counted from {@code start}, which must be a Monday. */
    public LocalDate workdayOf(LocalDate start, int index) {
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("start must be a Monday: " + start);
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (standard()) {
            return start.plusDays(7L * (index / 5) + index % 5);
        }
        LocalDate d = start;
        int seen = isWorkingDay(d) ? 0 : -1;
        while (seen < index) {
            d = d.plusDays(1);
            if (isWorkingDay(d)) {
                seen++;
            }
        }
        return d;
    }

    /** Holidays that fall on a weekday between two dates (both included): the working days they take. */
    public List<Holidays.Holiday> weekdayHolidays(LocalDate from, LocalDate to) {
        List<Holidays.Holiday> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek w = d.getDayOfWeek();
            if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDate day = d;
            holiday(d).ifPresent(name -> out.add(new Holidays.Holiday(day, name)));
        }
        return out;
    }

    /** Date of 0-based working day {@code index} on the standard calendar; {@code start} must be a Monday. */
    public static LocalDate workday(LocalDate start, int index) {
        return STANDARD.workdayOf(start, index);
    }

    /** Last calendar day allowed by a term of {@code months} starting on {@code start}. */
    public static LocalDate deadline(LocalDate start, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        return start.plusMonths(months).minusDays(1);
    }
}
