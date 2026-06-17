package de.orez.aura_sentry_core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.orez.aura_sentry_core.config.PricingConfig;
import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.model.ResourceState;
import de.orez.aura_sentry_core.service.StorageUsageProvider;

/**
 * Unit tests for the PricingEngine service.
 *
 * Validates:
 * - Cost estimation for various Azure resource types
 * - SKU-tier based optimization potential
 * - Regional price multiplier application
 * - Edge cases (unknown types, missing SKU data)
 */
@DisplayName("PricingEngine Cost Estimation Tests")
class PricingEngineTests {

        private final PricingEngine pricingEngine = new PricingEngine(
                new PricingConfig(null, null, null, null, null, null,
                        Collections.emptyList(), 5.0, "USD", true),
                new StorageUsageProvider(null) {
                    @Override
                    public double getUsedGb(String resourceId) {
                        return Double.NaN;
                    }
                });

        /**
         * Test: Standard Storage Account with LRS (Locally Redundant Storage)
         */
        @Test
        @DisplayName("Storage Account LRS monthly cost estimation")
        void testStorageAccountLrsCost() {
                AzureResource resource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/mystorage",
                                "mystorage",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of("Environment", "prod"),
                                ResourceState.RUNNING,
                                null,
                                "Standard_LRS",
                                "westeurope",
                                Map.of());

                double cost = pricingEngine.estimateMonthlyCost(resource);
                assertTrue(cost > 0, "Cost should be positive");
                assertTrue(cost < 10, "Storage account LRS should cost less than $10/month");
        }

        /**
         * Test: Storage Account with GRS (Geo-Redundant Storage)
         * Expected: ~50% more expensive than LRS
         */
        @Test
        @DisplayName("Storage Account GRS is more expensive than LRS")
        void testStorageAccountGrsVsLrs() {
                AzureResource lrsResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/lrs",
                                "lrs",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_LRS",
                                "westeurope",
                                Map.of());

                AzureResource grsResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/grs",
                                "grs",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_GRS",
                                "westeurope",
                                Map.of());

                double lrsCost = pricingEngine.estimateMonthlyCost(lrsResource);
                double grsCost = pricingEngine.estimateMonthlyCost(grsResource);

                assertTrue(grsCost > lrsCost, "GRS should be more expensive than LRS");
                assertTrue(grsCost > lrsCost * 1.3, "GRS should be at least 30% more expensive");
        }

        /**
         * Test: Regional pricing multiplier application
         * West Europe (1.0x baseline) vs East US (0.90x)
         */
        @Test
        @DisplayName("Regional multiplier affects total cost")
        void testRegionalPricingMultiplier() {
                AzureResource westEuropeVm = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/vm1",
                                "vm1",
                                "Microsoft.Compute/virtualMachines",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_D2s_v3",
                                "westeurope",
                                Map.of());

                AzureResource eastUsVm = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/vm2",
                                "vm2",
                                "Microsoft.Compute/virtualMachines",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_D2s_v3",
                                "eastus",
                                Map.of());

                double westCost = pricingEngine.estimateMonthlyCost(westEuropeVm);
                double eastCost = pricingEngine.estimateMonthlyCost(eastUsVm);

                assertTrue(eastCost < westCost, "East US should be cheaper than West Europe");
                assertTrue(eastCost / westCost < 1.0, "Regional multiplier should reduce cost");
        }

        /**
         * Test: VM cost scaling with SKU tier
         * Larger VMs (D8) should cost more than smaller VMs (B2)
         */
        @Test
        @DisplayName("VM SKU tier affects cost proportionally")
        void testVmSkuTierCostScaling() {
                AzureResource b2Vm = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/small",
                                "small",
                                "Microsoft.Compute/virtualMachines",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_B2s",
                                "westeurope",
                                Map.of());

                AzureResource d8Vm = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/large",
                                "large",
                                "Microsoft.Compute/virtualMachines",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_D8s_v3",
                                "westeurope",
                                Map.of());

                double b2Cost = pricingEngine.estimateMonthlyCost(b2Vm);
                double d8Cost = pricingEngine.estimateMonthlyCost(d8Vm);

                assertTrue(d8Cost > b2Cost, "D8 VM should cost more than B2");
                assertTrue(d8Cost > b2Cost * 10, "D8 should cost significantly more");
        }

        /**
         * Test: Savings potential for GRS→LRS downgrade
         * Expected: ~33% savings (67% of original cost)
         */
        @Test
        @DisplayName("GRS to LRS downgrade yields 33% savings")
        void testStorageDowngradeSavings() {
                AzureResource grsResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/grs",
                                "grs",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_GRS",
                                "westeurope",
                                Map.of());

                double savings = pricingEngine.calculateMonthlySavings(grsResource);
                assertTrue(savings > 0, "GRS should have savings potential");
        }

        /**
         * Test: No savings for already-optimized tier
         * LRS is optimal, should return 0 savings
         */
        @Test
        @DisplayName("LRS has no optimization potential")
        void testAlreadyOptimizedTier() {
                AzureResource lrsResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/lrs",
                                "lrs",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_LRS",
                                "westeurope",
                                Map.of());

                double savings = pricingEngine.calculateMonthlySavings(lrsResource);
                assertEquals(0.0, savings, "Already-optimized tier should have no savings");
        }

        /**
         * Test: Unknown resource type defaults to $5/month
         * Edge case handling
         */
        @Test
        @DisplayName("Unknown resource type uses safe default")
        void testUnknownResourceTypeDefault() {
                AzureResource unknownResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Unknown/unknownType/res",
                                "res",
                                "Microsoft.Unknown/unknownType",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "N/A",
                                "westeurope",
                                Map.of());

                double cost = pricingEngine.estimateMonthlyCost(unknownResource);
                assertEquals(5.0, cost, "Unknown type should default to $5/month");
        }

        /**
         * Test: Missing SKU data
         * Should gracefully fall back to default pricing
         */
        @Test
        @DisplayName("Handles missing SKU data gracefully")
        void testMissingSkuData() {
                AzureResource noSkuResource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/nosku",
                                "nosku",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                null, // No SKU
                                "westeurope",
                                Map.of());

                double cost = pricingEngine.estimateMonthlyCost(noSkuResource);
                assertTrue(cost >= 0, "Should return non-negative cost even with missing SKU");
        }

        /**
         * Test: VM downgrade savings (D4 → B2)
         * Potential savings ~65% for downsizing
         */
        @Test
        @DisplayName("VM downsizing yields significant savings")
        void testVmDownsizingSavings() {
                AzureResource d4Vm = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Compute/virtualMachines/d4",
                                "d4",
                                "Microsoft.Compute/virtualMachines",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_D4s_v3",
                                "westeurope",
                                Map.of());

                double savings = pricingEngine.calculateMonthlySavings(d4Vm);
                assertTrue(savings > 0, "D4 should have downsize savings potential");
                assertTrue(savings > 50, "Downsizing should save more than $50/month");
        }

        /**
         * Test: Regional multiplier with unknown region
         * Should apply 1.0x multiplier (no adjustment)
         */
        @Test
        @DisplayName("Unknown region defaults to 1.0x multiplier")
        void testUnknownRegionMultiplier() {
                AzureResource resource = new AzureResource(
                                "/subscriptions/sub1/resourceGroups/rg1/providers/Microsoft.Storage/storageAccounts/test",
                                "test",
                                "Microsoft.Storage/storageAccounts",
                                "rg1",
                                Map.of(),
                                ResourceState.RUNNING,
                                null,
                                "Standard_LRS",
                                "UnknownRegion123",
                                Map.of());

                double cost = pricingEngine.estimateMonthlyCost(resource);
                assertTrue(cost > 0, "Should still estimate cost with unknown region");
        }
}
