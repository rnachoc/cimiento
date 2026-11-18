package dev.cimiento.core.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
}
