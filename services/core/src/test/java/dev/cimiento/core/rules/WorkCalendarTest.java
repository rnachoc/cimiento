package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkCalendarTest {

    private static final LocalDate MONDAY = LocalDate.of(2027, 1, 4);

    @Test
    void workdaysSkipWeekends() {
        assertThat(WorkCalendar.workday(MONDAY, 0)).isEqualTo(MONDAY);
        assertThat(WorkCalendar.workday(MONDAY, 4)).isEqualTo(LocalDate.of(2027, 1, 8));
        assertThat(WorkCalendar.workday(MONDAY, 5)).isEqualTo(LocalDate.of(2027, 1, 11));
        assertThat(WorkCalendar.workday(MONDAY, 12)).isEqualTo(LocalDate.of(2027, 1, 20));
    }

    @Test
    void startMustBeMonday() {
        assertThatThrownBy(() -> WorkCalendar.workday(LocalDate.of(2027, 1, 5), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkCalendar.workday(MONDAY, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineIsStartPlusMonthsMinusOneDay() {
        assertThat(WorkCalendar.deadline(MONDAY, 2)).isEqualTo(LocalDate.of(2027, 3, 3));
        // month end clamps like the reference generator
        assertThat(WorkCalendar.deadline(LocalDate.of(2027, 1, 31), 1)).isEqualTo(LocalDate.of(2027, 2, 27));
        assertThatThrownBy(() -> WorkCalendar.deadline(MONDAY, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void easterAndTheNationalHolidaysOfSpain() {
        assertThat(Holidays.easterSunday(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(Holidays.easterSunday(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(Holidays.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(Holidays.easterSunday(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        var y2027 = Holidays.spainNational(2027);
        assertThat(y2027).hasSize(10);
        assertThat(y2027).contains(new Holidays.Holiday(LocalDate.of(2027, 3, 26), "Viernes Santo"),
                new Holidays.Holiday(LocalDate.of(2027, 10, 12), "Fiesta Nacional de España"));
        assertThat(y2027.get(0).date()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void theStandardCalendarIsTheOneTheGroundTruthUses() {
        var noHolidays = new WorkCalendar(WorkCalendar.Kind.NONE, Map.of());
        for (int i = 0; i < 600; i++) {
            LocalDate expected = MONDAY.plusDays(7L * (i / 5) + i % 5);
            assertThat(WorkCalendar.workday(MONDAY, i)).isEqualTo(expected);
            assertThat(WorkCalendar.STANDARD.workdayOf(MONDAY, i)).isEqualTo(expected);
            assertThat(noHolidays.workdayOf(MONDAY, i)).isEqualTo(expected);
        }
    }

    @Test
    void holidaysOnWeekdaysTakeAWorkingDay() {
        var national = new WorkCalendar(WorkCalendar.Kind.ES_NATIONAL, Map.of());
        // 2027-01-06 (Wednesday, Epiphany): ten working days from Monday 4 January end on Monday 18, not Friday 15
        assertThat(national.workdayOf(MONDAY, 9)).isEqualTo(LocalDate.of(2027, 1, 18));
        assertThat(national.isWorkingDay(LocalDate.of(2027, 1, 6))).isFalse();
        // Good Friday 2027-03-26
        assertThat(national.workdayOf(LocalDate.of(2027, 3, 22), 4)).isEqualTo(LocalDate.of(2027, 3, 29));
        // a local holiday on the first Monday moves day 0
        var local = new WorkCalendar(WorkCalendar.Kind.NONE, Map.of(MONDAY, "Fiesta local"));
        assertThat(local.workdayOf(MONDAY, 0)).isEqualTo(LocalDate.of(2027, 1, 5));
        assertThat(local.holiday(MONDAY)).contains("Fiesta local");
    }

    @Test
    void onlyHolidaysOnWeekdaysAreCounted() {
        var national = new WorkCalendar(WorkCalendar.Kind.ES_NATIONAL,
                Map.of(LocalDate.of(2027, 5, 15), "San Isidro"));
        // 2027-12-25 is a Saturday and 2027-05-15 a Saturday: neither takes a working day
        assertThat(national.weekdayHolidays(LocalDate.of(2027, 12, 1), LocalDate.of(2027, 12, 31)))
                .extracting(Holidays.Holiday::date)
                .containsExactly(LocalDate.of(2027, 12, 6), LocalDate.of(2027, 12, 8));
        assertThat(national.weekdayHolidays(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 31))).isEmpty();
    }
}
