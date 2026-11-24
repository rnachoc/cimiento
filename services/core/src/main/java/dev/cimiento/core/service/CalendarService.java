package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cimiento.core.domain.ScheduleEntity;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.repository.ScheduleRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.rules.Authority;
import dev.cimiento.core.rules.Holidays;
import dev.cimiento.core.rules.WorkCalendar;
import dev.cimiento.core.security.Actor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work calendar of each tender: which public holidays it takes out and its local holidays. The
 * standard calendar (no holidays) is the default; changing it is a person's act, checked and audited.
 */
@Service
public class CalendarService {

    /** Upper bound of local holidays per work: a year of regional and local days fits easily. */
    static final int MAX_LOCAL = 30;

    /** The calendar of a work, the holidays inside its span and what they do to the schedule end. */
    public record View(Long tenderId, String tenderCode, WorkCalendar.Kind calendar, List<Holidays.Holiday> localHolidays,
                       LocalDate from, LocalDate to, List<Holidays.Holiday> holidaysInSpan, Integer durationDays,
                       LocalDate endStandard, LocalDate endWithCalendar) {
    }

    private final TenderRepository tenders;
    private final ScheduleRepository schedules;
    private final AuditService audit;
    private final CanonicalJson json;

    public CalendarService(TenderRepository tenders, ScheduleRepository schedules, AuditService audit,
                           CanonicalJson json) {
        this.tenders = tenders;
        this.schedules = schedules;
        this.audit = audit;
        this.json = json;
    }

    /** The calendar a tender uses for its working days. */
    public WorkCalendar of(Tender t) {
        Map<LocalDate, String> local = new TreeMap<>();
        for (Holidays.Holiday h : local(t)) {
            local.put(h.date(), h.name());
        }
        return new WorkCalendar(t.getCalendar(), local);
    }

    private List<Holidays.Holiday> local(Tender t) {
        List<Holidays.Holiday> out = new ArrayList<>();
        JsonNode arr = json.read(t.getLocalHolidays());
        for (JsonNode n : arr) {
            out.add(new Holidays.Holiday(LocalDate.parse(n.path("date").asText()), n.path("name").asText()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public View view(Long tenderId) {
        Tender t = tenders.findById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        return view(t);
    }

    private View view(Tender t) {
        WorkCalendar cal = of(t);
        LocalDate start = t.getPlannedStart();
        Integer duration = schedules.findFirstByTenderIdOrderByIdDesc(t.getId())
                .map(ScheduleEntity::getDurationDays).orElse(null);
        LocalDate endStandard = duration == null ? null : WorkCalendar.workday(start, Math.max(duration - 1, 0));
        LocalDate endCal = duration == null ? null : cal.workdayOf(start, Math.max(duration - 1, 0));
        LocalDate to = endCal != null ? endCal
                : t.getContractTermMonths() != null ? WorkCalendar.deadline(start, t.getContractTermMonths())
                : start.plusYears(1).minusDays(1);
        return new View(t.getId(), t.getCode(), t.getCalendar(), local(t), start, to,
                cal.weekdayHolidays(start, to), duration, endStandard, endCal);
    }

    @Transactional
    public View update(Actor actor, Long tenderId, WorkCalendar.Kind kind, List<Holidays.Holiday> holidays) {
        if (actor.person() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "sign in as a person to change the work calendar");
        }
        List<Authority.Broken> broken = Authority.checkCalendar(actor.personRole());
        if (!broken.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "calendar change not allowed for " + actor.person(), broken);
        }
        Tender t = tenders.lockById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        List<String> errors = new ArrayList<>();
        Map<LocalDate, String> clean = new TreeMap<>();
        for (Holidays.Holiday h : holidays == null ? List.<Holidays.Holiday>of() : holidays) {
            String name = h.name() == null ? "" : h.name().strip();
            if (h.date() == null) {
                errors.add("every local holiday needs a date");
            } else if (name.isEmpty()) {
                errors.add("the local holiday on " + h.date() + " needs a name");
            } else if (clean.put(h.date(), name) != null) {
                errors.add("the date " + h.date() + " is repeated");
            }
        }
        if (clean.size() > MAX_LOCAL) {
            errors.add("at most " + MAX_LOCAL + " local holidays per work");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "calendar not valid", errors);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        clean.forEach((d, n) -> rows.add(Map.of("date", d.toString(), "name", n)));
        t.useCalendar(kind, json.write(rows));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", t.getCode());
        details.put("calendar", kind.name());
        details.put("localHolidays", clean.size());
        audit.append(actor, "CALENDAR_CHANGED", "tender", t.getId(), details, t.getProject().getCode());
        return view(t);
    }
}
