package no.metatrack.server.upload;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@Startup
@ApplicationScoped
public class SftpGoEventIntegrationStartup {
    private final SftpGoConfig config;
    private final SftpGoManagementService managementService;

    public SftpGoEventIntegrationStartup(SftpGoConfig config,
                                         SftpGoManagementService managementService) {
        this.config = config;
        this.managementService = managementService;
    }

    @PostConstruct
    public void initialize() {
        if (config.enabled()) {
            managementService.ensureUploadEventIntegration();
        }
    }
}