package no.metatrack.server.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class UploadBatchProvisioningService {
    private static final Logger LOG = Logger.getLogger(UploadBatchProvisioningService.class);

    @Inject
    UploadBatchProvisioningStateService stateService;

    @Inject
    SftpGoManagementService sftpGoManagementService;

    @Inject
    SftpGoConfig sftpGoConfig;

    @Inject
    UploadBatchConfig batchConfig;

    @Inject
    ObjectMapper objectMapper;

    public boolean provision(UUID batchId) {
        Optional<UploadBatchProvisioningClaim> claim = stateService.claim(batchId);
        if (claim.isEmpty()) return false;
        try {
            sftpGoManagementService.ensureUser(userPayload(claim.get()));
            boolean opened = stateService.markOpen(batchId);
            if (!opened) sftpGoManagementService.deleteUser(claim.get().username());
            return opened;
        } catch (RuntimeException exception) {
            String error = exception instanceof SftpGoIntegrationException
                    ? exception.getMessage()
                    : "SFTPGo account provisioning failed";
            LOG.warnf("SFTPGo account provisioning failed for batch %s: %s", batchId, error);
            stateService.recordFailure(batchId, error);
            return false;
        }
    }

    private ObjectNode userPayload(UploadBatchProvisioningClaim claim) {
        ObjectNode user = objectMapper.createObjectNode();
        user.put("username", claim.username());
        user.put("status", 1);
        user.put("home_dir", "/srv/sftpgo/data/" + claim.username());
        user.put("max_sessions", batchConfig.maxTransfersPerBatch());
        user.put("expiration_date", claim.expiresAt().toEpochMilli());
        user.put("quota_size", Math.max(1, claim.reservedBytes()));
        user.put("quota_files", claim.fileCount());
        user.putArray("public_keys").add(claim.publicKey());

        ObjectNode permissions = user.putObject("permissions");
        ArrayNode rootPermissions = permissions.putArray("/");
        rootPermissions.add("list").add("upload").add("overwrite").add("create_dirs");
        user.putObject("filters").putArray("allowed_protocols").add("SFTP");

        ObjectNode filesystem = user.putObject("filesystem");
        filesystem.put("provider", 1);
        ObjectNode s3 = filesystem.putObject("s3config");
        s3.put("bucket", sftpGoConfig.s3BucketName().orElseThrow());
        s3.put("region", sftpGoConfig.s3Region().orElseThrow());
        s3.put("endpoint", sftpGoConfig.s3Endpoint().orElseThrow());
        s3.put("access_key", sftpGoConfig.s3AccessKey().orElseThrow());
        s3.putObject("access_secret")
                .put("status", "Plain")
                .put("payload", sftpGoConfig.s3SecretKey().orElseThrow());
        s3.put("key_prefix", "staging/" + claim.batchId() + "/");
        s3.put("force_path_style", sftpGoConfig.s3PathStyleAccess());
        return user;
    }
}