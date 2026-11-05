package no.metatrack.server.isa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import no.metatrack.server.file.ReadRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IsaJsonImportServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsTaxonomyIdsInTheCommonNotations() {
        assertEquals(287, IsaSampleFields.taxId("287"));
        assertEquals(3702, IsaSampleFields.taxId("NCBI:txid3702"));
        assertEquals(9606, IsaSampleFields.taxId("http://purl.obolibrary.org/obo/NCBITaxon_9606"));
        assertThrows(IllegalArgumentException.class, () -> IsaSampleFields.taxId("Arabidopsis thaliana"));
    }

    @Test
    void matchesRegisterNamesIgnoringCaseSpacesAndUnderscores() {
        assertEquals("collectionDate", IsaSampleFields.find("Collection_Date").orElseThrow().property());
        assertEquals("sampleAccession", IsaSampleFields.find("accession").orElseThrow().property());
        assertEquals("location", IsaSampleFields.find("geographic location (country and/or sea)").orElseThrow().property());
    }

    @Test
    void derivesReadRolesFromCommentsLayoutOrFileName() {
        assertEquals(ReadRole.REVERSE, IsaJsonImportService.readRole("reverse", "x.fastq", "PAIRED"));
        assertEquals(ReadRole.SINGLE, IsaJsonImportService.readRole(null, "S_R1.fastq.gz", "SINGLE"));
        assertEquals(ReadRole.FORWARD, IsaJsonImportService.readRole(null, "S_R1.fastq.gz", "PAIRED"));
        assertEquals(ReadRole.REVERSE, IsaJsonImportService.readRole(null, "S_2.fq.gz", null));
        assertNull(IsaJsonImportService.readRole(null, "ISA-DEMO-001.fastq", null));
    }

    @Test
    void buildsValidFieldKeysAndReadableAssayNames() {
        assertEquals("growth_condition", IsaJsonImportService.keyFor("growth condition"));
        assertEquals("isa_location", IsaJsonImportService.keyFor("Location"));
        assertEquals("f_16s_region", IsaJsonImportService.keyFor("16S region"));
        assertEquals("RNASeq", IsaJsonImportService.assayName("assays/RNASeq/isa.assay"));
        assertEquals("wgs", IsaJsonImportService.assayName("a_wgs.txt"));
    }

    @Test
    void acceptsOntologyAnnotationsStringsAndNumbersAsValues() {
        assertEquals("Norway", IsaJsonImportService.text(MAPPER.createObjectNode().put("annotationValue", "Norway")));
        assertEquals("12.50", IsaJsonImportService.text(MAPPER.getNodeFactory().numberNode(new java.math.BigDecimal("12.50"))));
        assertNull(IsaJsonImportService.text(MAPPER.getNodeFactory().textNode("  ")));
    }

    /** One study with one sample carrying a custom characteristic and a collection date. */
    static JsonNode minimalDocument(String sampleName, String collectionDate) {
        ObjectNode study = MAPPER.createObjectNode().put("identifier", "s1").put("title", "Minimal");
        study.putArray("characteristicCategories")
                .add(category("#c/date", "collection date"))
                .add(category("#c/soil", "soil type"));
        ObjectNode sample = study.putObject("materials").putArray("samples").addObject()
                .put("@id", "#sample/1").put("name", sampleName);
        sample.putArray("characteristics")
                .add(characteristic("#c/date", collectionDate))
                .add(characteristic("#c/soil", "loam"));
        ObjectNode investigation = MAPPER.createObjectNode();
        investigation.putArray("studies").add(study);
        return investigation;
    }

    private static ObjectNode category(String id, String name) {
        ObjectNode category = MAPPER.createObjectNode().put("@id", id);
        category.putObject("characteristicType").put("annotationValue", name);
        return category;
    }

    private static ObjectNode characteristic(String categoryId, String value) {
        ObjectNode characteristic = MAPPER.createObjectNode();
        characteristic.putObject("category").put("@id", categoryId);
        characteristic.putObject("value").put("annotationValue", value);
        return characteristic;
    }
}
