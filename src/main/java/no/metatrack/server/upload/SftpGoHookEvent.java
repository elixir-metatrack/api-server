package no.metatrack.server.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SftpGoHookEvent(String event, String username, String path, int status, long sizeBytes) {
    public SftpGoHookEvent withDecodedPath(String decodedPath) {
        return new SftpGoHookEvent(event, username, decodedPath, status, sizeBytes);
    }
}