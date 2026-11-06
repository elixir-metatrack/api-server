package no.metatrack.server.upload;

import no.metatrack.server.file.SubProjectTestProfile;

import java.util.HashMap;
import java.util.Map;

public class UploadBatchRegistrationTestProfile extends SubProjectTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>(super.getConfigOverrides());
        config.put("metatrack.sftpgo.enabled", "false");
        config.put("metatrack.sftpgo.s3-endpoint", "http://localhost:1");
        config.put("metatrack.sftpgo.s3-region", "us-east-1");
        config.put("metatrack.sftpgo.s3-access-key", "test");
        config.put("metatrack.sftpgo.s3-secret-key", "test");
        config.put("metatrack.sftpgo.s3-bucket-name", "test");
        config.put("metatrack.sftpgo.s3-path-style-access", "true");
        config.put("metatrack.sftpgo.resume-max-size-bytes", "53687091200");
        config.put("metatrack.upload-batches.staging-budget-bytes", "1000000000");
        config.put("quarkus.scheduler.enabled", "false");
        return config;
    }
}