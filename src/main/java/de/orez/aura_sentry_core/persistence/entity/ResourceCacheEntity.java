package de.orez.aura_sentry_core.persistence.entity;

import java.time.Instant;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto.IssueType;
import de.orez.aura_sentry_core.model.ResourceState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Current-state cache of Azure resources.
 *
 * <p>Unlike {@link ScanResultEntity} (immutable scan history), this table
 * stores the <em>live</em> view of each resource. Every Azure sync performs
 * an upsert into this table so that existing entries are overwritten with
 * fresh data instead of accumulating historical rows.
 *
 * <p>The {@code cost} field is eagerly refreshed from the Azure Cost
 * Management API on every {@code GET /api/resources} call.
 */
@Entity
@Table(name = "resource_cache")
public class ResourceCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String azureId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String type;

    @Column(name = "resource_group", length = 255)
    private String resourceGroup;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ResourceState state;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private IssueType flag;

    private Double cost;

    @Column(length = 10)
    private String currency = "USD";

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(length = 255)
    private String sku;

    @Column(length = 255)
    private String region;

    protected ResourceCacheEntity() {
    }

    public ResourceCacheEntity(String azureId, String name, String type,
                               String resourceGroup, ResourceState state,
                               String sku, String region) {
        this.azureId = azureId;
        this.name = name;
        this.type = type;
        this.resourceGroup = resourceGroup;
        this.state = state;
        this.sku = sku;
        this.region = region;
    }

    @PrePersist
    public void onPrePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        scannedAt = now;
    }

    @PreUpdate
    public void onPreUpdate() {
        Instant now = Instant.now();
        updatedAt = now;
        scannedAt = now;
    }

    public Long getId() { return id; }
    public String getAzureId() { return azureId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getResourceGroup() { return resourceGroup; }
    public ResourceState getState() { return state; }
    public IssueType getFlag() { return flag; }
    public Double getCost() { return cost; }
    public String getCurrency() { return currency; }
    public Instant getScannedAt() { return scannedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setAzureId(String azureId) { this.azureId = azureId; }
    public void setName(String name) { this.name = name; }
    public void setType(String type) { this.type = type; }
    public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }
    public void setState(ResourceState state) { this.state = state; }
    public void setFlag(IssueType flag) { this.flag = flag; }
    public void setCost(Double cost) { this.cost = cost; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getSku() { return sku; }
    public String getRegion() { return region; }

    public void setSku(String sku) { this.sku = sku; }
    public void setRegion(String region) { this.region = region; }
    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}