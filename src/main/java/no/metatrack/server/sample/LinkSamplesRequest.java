package no.metatrack.server.sample;

import java.util.List;
import java.util.UUID;

public record LinkSamplesRequest(List<UUID> sampleIds) {}
