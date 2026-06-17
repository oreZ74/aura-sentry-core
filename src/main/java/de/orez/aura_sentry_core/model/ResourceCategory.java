package de.orez.aura_sentry_core.model;

/**
 * Sealed interface for type-safe resource categories.
 * Enables exhaustive pattern matching for switch in the rules engine.
 */
public sealed interface ResourceCategory
        permits ResourceCategory.VirtualMachine,
        ResourceCategory.StorageAccount,
        ResourceCategory.NetworkInterface,
        ResourceCategory.Other {

    AzureResource resource();

    record VirtualMachine(AzureResource resource) implements ResourceCategory {
    }

    record StorageAccount(AzureResource resource) implements ResourceCategory {
    }

    record NetworkInterface(AzureResource resource) implements ResourceCategory {
    }

    record Other(AzureResource resource) implements ResourceCategory {
    }

    /**
     * Factory method: categorises an AzureResource based on its type string.
     * Uses Java 25 pattern matching for switch with guard clauses.
     */
    static ResourceCategory of(AzureResource resource) {
        String type = resource.type() == null ? "" : resource.type().toLowerCase();
        return switch (type) {
            case String t when t.contains("microsoft.compute/virtualmachines") -> new VirtualMachine(resource);
            case String t when t.contains("microsoft.storage/storageaccounts") -> new StorageAccount(resource);
            case String t when t.contains("microsoft.network/networkinterfaces") -> new NetworkInterface(resource);
            default -> new Other(resource);
        };
    }
}
