package no.metatrack.server.upload;

public interface SftpGoUploadPolicy {
    boolean permits(SftpGoHookEvent event);
}