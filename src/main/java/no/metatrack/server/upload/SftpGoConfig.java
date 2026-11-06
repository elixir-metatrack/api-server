package no.metatrack.server.upload;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "metatrack.sftpgo")
public interface SftpGoConfig {
    boolean enabled();

    String apiUrl();

    Optional<String> adminUsername();

    Optional<String> adminPassword();

    Optional<String> sftpHost();

    int sftpPort();

    Optional<String> s3Endpoint();

    Optional<String> s3Region();

    Optional<String> s3AccessKey();

    Optional<String> s3SecretKey();

    Optional<String> s3BucketName();

    boolean s3PathStyleAccess();

    Optional<String> hookUrl();

    Optional<String> hookSecret();

    long resumeMaxSizeBytes();
}