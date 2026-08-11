package no.metatrack.server.sample;

import no.metatrack.server.sample.vocabulary.SampleVocabularyRules;
import no.metatrack.server.sample.vocabulary.SampleVocabularyService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CSVSampleSheetImportServiceTest {
    @Test
    void exposesCanonicalKeysForBuiltInAliases() {
        Sample sample = new Sample();
        sample.sequencingLab = "Lab A";
        sample.isolationSource = "swab";
        sample.hostSex = "female";

        Map<String, String> values = CSVSampleSheetImportService.builtInValues(sample);

        assertEquals("Lab A", values.get("sequencing_lab"));
        assertEquals("swab", values.get("isolation_source"));
        assertEquals("female", values.get("host_sex"));
        assertTrue(values.keySet().stream().noneMatch(key -> key.contains(" ")));
    }

    @Test
    void validatesMultipleRowsAgainstOneLoadedRuleSnapshot() {
        SampleVocabularyRules rules = new SampleVocabularyRules(
                Set.of("custom_status"), Map.of("host_sex", Set.of("female"), "custom_status", Set.of("known")));

        assertTrue(SampleVocabularyService.validate(
                        rules, "sample-1", Map.of("host_sex", "female"), Map.of("custom_status", "known"))
                .isEmpty());
        assertEquals(2, SampleVocabularyService.validate(
                        rules, "sample-2", Map.of("host_sex", "male"), Map.of("custom_status", "unknown"))
                .size());
    }
}