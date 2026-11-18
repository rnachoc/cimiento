package dev.cimiento.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.cimiento.core.domain.Proposal;
import dev.cimiento.core.domain.ProposalStatus;
import dev.cimiento.core.domain.Tender;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.rules.Payloads;
import dev.cimiento.core.rules.ProposalType;
import dev.cimiento.core.rules.Site;
import dev.cimiento.core.rules.WorkCalendar;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Site view of a tender: the latest live schedule and weekly progress (pending or executed, and
 * says which), analysed by {@link Site}. Nothing here is written; it is a read of what the rules
 * already accepted.
 */
@Service
public class SiteService {

    public record SiteView(Long tenderId, String tenderCode, int statusWeek, LocalDate cutDate, boolean progressApproved,
                           boolean scheduleApproved, LocalDate plannedEnd, LocalDate forecastEnd,
                           LocalDate contractDeadline, Site.Result analysis) {
    }

    private final TenderRepository tenders;
    private final PortfolioService portfolio;
    private final CanonicalJson json;

    public SiteService(TenderRepository tenders, PortfolioService portfolio, CanonicalJson json) {
        this.tenders = tenders;
        this.portfolio = portfolio;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public SiteView site(Long tenderId) {
        Tender t = tenders.findById(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " not found"));
        Proposal sched = portfolio.latestLive(tenderId, ProposalType.SCHEDULE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " has no schedule"));
        Proposal prog = portfolio.latestLive(tenderId, ProposalType.PROGRESS_UPDATE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "tender " + tenderId + " has no site progress"));
        try {
            var schedule = json.mapper().treeToValue(portfolio.contentOf(sched), Payloads.Schedule.class);
            var progress = json.mapper().treeToValue(portfolio.contentOf(prog), Payloads.Progress.class);
            Map<Integer, List<Site.Row>> weeks = new HashMap<>();
            for (Payloads.ProgressWeek w : progress.historyOrEmpty()) {
                weeks.put(w.week(), rows(w.rows()));
            }
            weeks.put(progress.week(), rows(progress.rows()));
            Site.Result r = Site.analyse(schedule.activities(), schedule.links(), weeks);
            LocalDate start = t.getPlannedStart();
            return new SiteView(t.getId(), t.getCode(), r.statusWeek(), WorkCalendar.workday(start, 5 * r.statusWeek() - 1),
                    prog.getStatus() == ProposalStatus.EXECUTED, sched.getStatus() == ProposalStatus.EXECUTED,
                    WorkCalendar.workday(start, r.plannedDuration() - 1),
                    WorkCalendar.workday(start, r.forecastDuration() - 1),
                    t.getContractTermMonths() == null ? null : WorkCalendar.deadline(start, t.getContractTermMonths()),
                    r);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "site view cannot be computed: " + e.getMessage());
        }
    }

    private static List<Site.Row> rows(List<Payloads.ProgressRow> rows) {
        return rows.stream().map(r -> new Site.Row(r.activity(), r.plannedPct(), r.actualPct())).toList();
    }
}
