package de.orez.aura_sentry_core.service;

import de.orez.aura_sentry_core.model.AzureResource;

import java.util.List;

public interface CloudProviderService {

    List<AzureResource> fetchResources();
}
