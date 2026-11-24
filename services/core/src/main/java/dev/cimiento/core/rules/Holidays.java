package dev.cimiento.core.rules;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * National public holidays common to every region of Spain. Regional and local holidays differ by
 * place and year, so they are entered per work (see {@link WorkCalendar}); nothing here is moved when
 * a holiday falls on a Sunday, because that choice is also regional.
 */
public final class Holidays {

    /** One day off with its name. */
    public record Holiday(LocalDate date, String name) {
    }

    private Holidays() {
    }

    /** Easter Sunday in the Gregorian calendar (anonymous algorithm, valid for any year). */
    public static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    /** The ten national holidays of a year, in date order. */
    public static List<Holiday> spainNational(int year) {
        List<Holiday> out = new ArrayList<>(List.of(
                new Holiday(LocalDate.of(year, Month.JANUARY, 1), "Año Nuevo"),
                new Holiday(LocalDate.of(year, Month.JANUARY, 6), "Epifanía del Señor"),
                new Holiday(easterSunday(year).minusDays(2), "Viernes Santo"),
                new Holiday(LocalDate.of(year, Month.MAY, 1), "Fiesta del Trabajo"),
                new Holiday(LocalDate.of(year, Month.AUGUST, 15), "Asunción de la Virgen"),
                new Holiday(LocalDate.of(year, Month.OCTOBER, 12), "Fiesta Nacional de España"),
                new Holiday(LocalDate.of(year, Month.NOVEMBER, 1), "Todos los Santos"),
                new Holiday(LocalDate.of(year, Month.DECEMBER, 6), "Día de la Constitución"),
                new Holiday(LocalDate.of(year, Month.DECEMBER, 8), "Inmaculada Concepción"),
                new Holiday(LocalDate.of(year, Month.DECEMBER, 25), "Navidad")));
        out.sort(Comparator.comparing(Holiday::date));
        return out;
    }
}
