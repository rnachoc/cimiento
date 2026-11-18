package dev.cimiento.core.service;

import dev.cimiento.core.domain.AuditEntry;
import dev.cimiento.core.domain.ScheduleActivity;
import dev.cimiento.core.domain.ScheduleEntity;
import dev.cimiento.core.repository.AuditRepository;
import dev.cimiento.core.repository.ProgressRepository;
import dev.cimiento.core.repository.RateRepository;
import dev.cimiento.core.repository.ScheduleActivityRepository;
import dev.cimiento.core.repository.ScheduleLinkRepository;
import dev.cimiento.core.repository.ScheduleRepository;
import dev.cimiento.core.repository.TenderRepository;
import dev.cimiento.core.web.ApiDtos.ActivityView;
import dev.cimiento.core.web.ApiDtos.LinkView;
import dev.cimiento.core.web.ApiDtos.ProgressView;
import dev.cimiento.core.web.ApiDtos.RateView;
import dev.cimiento.core.web.ApiDtos.ScheduleView;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views: executed schedules, progress, audit and rates. */
@Service
@Transactional(readOnly = true)
public class QueryService {

    private final TenderRepository tenders;
    private final ScheduleRepository schedules;
    private final ScheduleActivityRepository activities;
    private final ScheduleLinkRepository links;
    private final ProgressRepository progress;
    private final AuditRepository audit;
    private final RateRepository rates;

    public QueryService(TenderRepository tenders, ScheduleRepository schedules, ScheduleActivityRepository activities,
                        ScheduleLinkRepository links, ProgressRepository progress, AuditRepository audit,
                        RateRepository rates) {
        this.tenders = tenders;
        this.schedules = schedules;
        this.activities = activities;
        this.links = links;
        this.progress = progress;
        this.audit = audit;
        this.rates = rates;
    }

    public ScheduleView latestSchedule(Long tenderId) {
        requireTender(tenderId);
        ScheduleEntity s = schedules.findFirstByTenderIdOrderByIdDesc(tenderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no executed schedule for tender " + tenderId));
        List<ScheduleActivity> acts = activities.findByScheduleIdOrderByCode(s.getId());
        List<String> critical = acts.stream().filter(ScheduleActivity::isCritical)
                .sorted(Comparator.comparingInt(ScheduleActivity::getEs).thenComparing(ScheduleActivity::getCode))
                .map(ScheduleActivity::getCode).toList();
        return new ScheduleView(s.getId(), s.getTenderId(), s.getProposalId(), s.getDurationDays(), s.getStartDate(),
                s.getEndDate(), s.getContractDeadline(), s.isExceedsTerm(), critical,
                acts.stream().map(a -> new ActivityView(a.getCode(), a.getName(), a.getDuration(), a.getEs(),
                        a.getEf(), a.getLs(), a.getLf(), a.getTotalFloat(), a.isCritical())).toList(),
                links.findByScheduleIdOrderByFromCodeAscToCodeAsc(s.getId()).stream()
                        .map(l -> new LinkView(l.getFromCode(), l.getToCode(), l.getLag())).toList());
    }

    public List<ProgressView> progress(Long tenderId) {
        requireTender(tenderId);
        return progress.findByTenderIdOrderByWeekAscActivityCodeAsc(tenderId).stream()
                .map(p -> new ProgressView(p.getWeek(), p.getActivityCode(), p.getPlannedPct(), p.getActualPct()))
                .toList();
    }

    public List<AuditEntry> audit(Long afterSeq) {
        return audit.findBySeqGreaterThanOrderBySeqAsc(afterSeq == null ? 0L : afterSeq);
    }

    public List<RateView> rates() {
        return rates.findAll().stream().map(r -> {
            var rate = r.toRate();
            return new RateView(rate.code(), r.getDescription(), rate.activity(), rate.unit(),
                    rate.unitPrice().toPlainString(), rate.ratePerDay().toPlainString());
        }).sorted(Comparator.comparing(RateView::code)).toList();
    }

    private void requireTender(Long id) {
        if (!tenders.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "tender " + id + " not found");
        }
    }
}
