package no.metatrack.server.sample;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record LinkSamplesRequest(@NotNull List<@NotNull UUID> sampleIds) {}
