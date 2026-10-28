package com.ripplemap.service;

import com.ripplemap.dto.SettingsDtos;
import com.ripplemap.entity.AppSettings;
import com.ripplemap.exception.ApiException;
import com.ripplemap.impact.ImpactThresholds;
import com.ripplemap.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;

@Service
public class SettingsService {

    private final AppSettingsRepository repository;
    private final Clock clock;

    public SettingsService(AppSettingsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsDtos.Settings get() {
        return toDto(load());
    }

    @Transactional(readOnly = true)
    public ImpactThresholds thresholds() {
        AppSettings settings = load();
        return new ImpactThresholds(settings.getLowThreshold(), settings.getHighThreshold());
    }

    @Transactional(readOnly = true)
    public boolean includeInferredByDefault() {
        return load().isIncludeInferred();
    }

    @Transactional
    public SettingsDtos.Settings update(SettingsDtos.UpdateSettingsRequest request) {
        if (request.lowThreshold().compareTo(request.highThreshold()) >= 0) {
            throw ApiException.validation("highThreshold",
                    "The medium/high threshold must be greater than the low/medium threshold");
        }
        ImpactThresholds validated = new ImpactThresholds(request.lowThreshold(), request.highThreshold());
        AppSettings settings = load();
        settings.update(validated.low().setScale(2, RoundingMode.UNNECESSARY),
                validated.high().setScale(2, RoundingMode.UNNECESSARY), request.includeInferred(),
                request.explorerNodeLimit(), clock.instant());
        return toDto(settings);
    }

    private AppSettings load() {
        return repository.findById(AppSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Settings row is missing"));
    }

    private static SettingsDtos.Settings toDto(AppSettings s) {
        return new SettingsDtos.Settings(s.getLowThreshold(), s.getHighThreshold(), s.isIncludeInferred(),
                s.getExplorerNodeLimit(), s.getUpdatedAt());
    }
}
