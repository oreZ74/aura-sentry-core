package de.orez.aura_sentry_core.persistence.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.orez.aura_sentry_core.model.ResourceState;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "scan_results")
public class ScanResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant scannedAt;

    @Column(nullable = false)
    private String resourceId;

    @Column(nullable = false)
    private String resourceName;

    private String resourceType;

    private String resourceGroup;

    @Enumerated(EnumType.STRING)
    private ResourceState state;

    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ScanFindingEntity> findings = new ArrayList<>();

    @Column(nullable = false)
    private boolean aiLocked = false;

    protected ScanResultEntity() {
    }

    public ScanResultEntity(Instant scannedAt, String resourceId, String resourceName,
            String resourceType, String resourceGroup, ResourceState state) {
        this.scannedAt = scannedAt;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.resourceGroup = resourceGroup;
        this.state = state;
    }

    public void addFinding(ScanFindingEntity finding) {
        findings.add(finding);
        finding.setScanResult(this);
    }

    public UUID getId() {
        return id;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceGroup() {
        return resourceGroup;
    }

    public ResourceState getState() {
        return state;
    }

    public List<ScanFindingEntity> getFindings() {
        return findings;
    }

    public boolean isAiLocked() {
        return aiLocked;
    }

    public void setAiLocked(boolean aiLocked) {
        this.aiLocked = aiLocked;
    }
}
