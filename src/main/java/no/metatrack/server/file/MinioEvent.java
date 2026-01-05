package no.metatrack.server.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MinioEvent(List<Record> Records) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Record(S3 s3, String eventName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record S3(Bucket bucket, ObjectData object) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bucket(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectData(String key, Long size, String eTag) {}
}
