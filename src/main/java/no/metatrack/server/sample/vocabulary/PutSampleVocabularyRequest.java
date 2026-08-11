package no.metatrack.server.sample.vocabulary;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PutSampleVocabularyRequest(@NotNull @NotEmpty List<@NotNull String> terms) {}