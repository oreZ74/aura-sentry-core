package de.orez.aura_sentry_core.service.demo;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import de.orez.aura_sentry_core.model.AzureResource;

/**
 * Offline demo provider that loads simulated Azure resources from a local
 * JSON file ({@code demo-resources.json}).
 *
 * <p>Used when {@code X-Demo-Mode: true} is set – no Azure subscription,
 * no service principal, and no network connectivity required.
 */
@Service
public class DemoCloudProviderService {

    private static final Logger log = LoggerFactory.getLogger(DemoCloudProviderService.class);

    private final List<AzureResource> demoResources;

    public DemoCloudProviderService() throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

        this.demoResources = mapper.readValue(
                new ClassPathResource("demo-data/demo-resources.json").getInputStream(),
                new TypeReference<>() {});

        log.info("DemoCloudProviderService loaded {} mock Azure resources", demoResources.size());
    }

    public List<AzureResource> fetchResources() {
        log.debug("Returning {} demo resources", demoResources.size());
        return List.copyOf(demoResources);
    }
}
