package de.orez.aura_sentry_core.persistence.entity;

import java.util.UUID;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "scan_findings")
public class ScanFindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueType flag;

    @Column(nullable = false, length = 1000)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResultEntity scanResult;

    protected ScanFindingEntity() {
    }

    public ScanFindingEntity(IssueType flag, String reason) {
        this.flag = flag;
        this.reason = reason;
    }

    void setScanResult(ScanResultEntity scanResult) {
        this.scanResult = scanResult;
    }

    public UUID getId() {
        return id;
    }

    public IssueType getFlag() {
        return flag;
    }

    public String getReason() {
        return reason;
    }

    public ScanResultEntity getScanResult() {
        return scanResult;
    }
}
