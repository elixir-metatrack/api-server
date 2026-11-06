package no.metatrack.server.upload;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SftpGoBatchUploadPolicy implements SftpGoUploadPolicy {
    @Inject
    SftpGoManagementService sftpGoManagementService;

    @Inject
    UploadBatchAdmissionService admissionService;

    @Override
    public boolean permits(SftpGoHookEvent event) {
        if ("upload".equals(event.event())) {
            return admissionService.recordCompleted(event);
        }
        if (!"pre-upload".equals(event.event())) return false;
        return admissionService.admit(event, sftpGoManagementService.activeConnections());
    }
}