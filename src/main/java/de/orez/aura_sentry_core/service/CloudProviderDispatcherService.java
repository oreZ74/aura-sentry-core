package de.orez.aura_sentry_core.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.orez.aura_sentry_core.model.AzureResource;
import de.orez.aura_sentry_core.service.demo.DemoCloudProviderService;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;

/**
 * Delegating dispatcher that chooses between the real {@link AzureResourceService}
 * and the offline {@link DemoCloudProviderService} based on the current
 * {@link DemoModeContext}.
 *
 * <p>This is the single injection point for {@link CloudProviderService} –
 * controllers and the dashboard inject this dispatcher instead of either
 * concrete implementation.
 */
@Service
public class CloudProviderDispatcherService implements CloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(CloudProviderDispatcherService.class);

    private final AzureResourceService azureResourceService;
    private final DemoCloudProviderService demoCloudProviderService;
    private final DemoModeProvider demoModeProvider;

    public CloudProviderDispatcherService(AzureResourceService azureResourceService,
                                            DemoCloudProviderService demoCloudProviderService,
                                            DemoModeProvider demoModeProvider) {
        this.azureResourceService = azureResourceService;
        this.demoCloudProviderService = demoCloudProviderService;
        this.demoModeProvider = demoModeProvider;
    }

    @Override
    public List<AzureResource> fetchResources() {
        if (demoModeProvider.isDemoMode()) {
            log.info("Demo mode active – using demo resource provider");
            return demoCloudProviderService.fetchResources();
        }
        log.info("Using Azure resource provider");
        return azureResourceService.fetchResources();
    }
}
