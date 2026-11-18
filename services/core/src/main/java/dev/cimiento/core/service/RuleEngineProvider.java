package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.domain.RateEntity;
import dev.cimiento.core.repository.RateRepository;
import dev.cimiento.core.rules.Rate;
import dev.cimiento.core.rules.RuleEngine;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds the rule engine from the company rates stored in the database. */
@Service
public class RuleEngineProvider {

    private final RateRepository rates;
    private final ObjectMapper mapper;
    private final BigDecimal amountThreshold;

    public RuleEngineProvider(RateRepository rates, ObjectMapper mapper,
                              @Value("${cimiento.review.amount-threshold-eur}") BigDecimal amountThreshold) {
        this.rates = rates;
        this.mapper = mapper;
        this.amountThreshold = amountThreshold;
    }

    public RuleEngine engine() {
        Map<String, Rate> table = rates.findAll().stream().map(RateEntity::toRate)
                .collect(Collectors.toMap(Rate::code, r -> r));
        return new RuleEngine(table, amountThreshold, mapper);
    }
}
