package no.metatrack.server.spreadsheet;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.sample.vocabulary.SampleVocabularyResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class SpreadsheetExportWriter {
    private record SampleColumn(String header, Function<Sample, Object> value) {}

    private static final List<SampleColumn> SAMPLE_COLUMNS = List.of(
            new SampleColumn("name", s -> s.name),
            new SampleColumn("alias", s -> s.alias),
            new SampleColumn("tax_id", s -> s.taxId),
            new SampleColumn("host_tax_id", s -> s.hostTaxId),
            new SampleColumn("mlst", s -> s.mlst),
            new SampleColumn("isolation_source", s -> s.isolationSource),
            new SampleColumn("collection_date", s -> s.collectionDate),
            new SampleColumn("location", s -> s.location),
            new SampleColumn("sequencing_lab", s -> s.sequencingLab),
            new SampleColumn("institution", s -> s.institution),
            new SampleColumn("host_health_state", s -> s.hostHealthState),
            new SampleColumn("project_title", s -> s.projectTitle),
            new SampleColumn("description", s -> s.description),
            new SampleColumn("isolate", s -> s.isolate),
            new SampleColumn("collected_by", s -> s.collectedBy),
            new SampleColumn("latitude", s -> s.latitude),
            new SampleColumn("longitude", s -> s.longitude),
            new SampleColumn("environmental_sample", s -> s.environmentalSample),
            new SampleColumn("host_associated", s -> s.hostAssociated),
            new SampleColumn("host_common_name", s -> s.hostCommonName),
            new SampleColumn("host_subject_id", s -> s.hostSubjectId),
            new SampleColumn("collector_name", s -> s.collectorName),
            new SampleColumn("collecting_institution", s -> s.collectingInstitution),
            new SampleColumn("host_sex", s -> s.hostSex),
            new SampleColumn("influenza_test_method", s -> s.influenzaTestMethod),
            new SampleColumn("influenza_test_result", s -> s.influenzaTestResult),
            new SampleColumn("other_pathogens_tested", s -> s.otherPathogensTested),
            new SampleColumn("other_pathogens_test_result", s -> s.otherPathogensTestResult),
            new SampleColumn("host_habitat", s -> s.hostHabitat),
            new SampleColumn("isolation_source_host_associated", s -> s.isolationSourceHostAssociated),
            new SampleColumn("host_behaviour", s -> s.hostBehaviour),
            new SampleColumn("isolation_source_non_host_associated", s -> s.isolationSourceNonHostAssociated),
            new SampleColumn("influenza_virus_type", s -> s.influenzaVirusType),
            new SampleColumn("influenza_sub_type", s -> s.influenzaSubType),
            new SampleColumn("serovar", s -> s.serovar),
            new SampleColumn("strain", s -> s.strain),
            new SampleColumn("host_age", s -> s.hostAge),
            new SampleColumn("county", s -> s.county),
            new SampleColumn("commune", s -> s.commune),
            new SampleColumn("hospital_health_institution", s -> s.hospitalHealthInstitution));

    private static final List<String> EXPERIMENT_HEADERS = List.of(
            "Sample", "Study Accession", "Sequencing instrument", "Library Name", "Library Source",
            "Library Selection", "Library Strategy", "Library Layout", "Insert Size", "Sequencing platform",
            "Sequencing Laboratory", "File Name", "File md5", "File Unencrypted md5",
            "Forward File Name", "Forward File md5", "Forward File Unencrypted md5",
            "Reverse File Name", "Reverse File md5", "Reverse File Unencrypted md5");

    public byte[] samples(List<Sample> samples, List<SampleMetadataField> fields,
            Map<UUID, Map<String, Object>> metadata, List<SampleVocabularyResponse> vocabularies) {
        List<SampleMetadataField> active = fields.stream().filter(field -> field.archivedOn == null).toList();
        List<String> headers = new ArrayList<>(SAMPLE_COLUMNS.stream().map(SampleColumn::header).toList());
        active.forEach(field -> headers.add(field.key));
        try (Workbook workbook = new XSSFWorkbook()) {
            ExportTable table = new ExportTable(workbook, "Samples", headers);
            for (Sample sample : samples) {
                List<Object> values = new ArrayList<>();
                SAMPLE_COLUMNS.forEach(column -> values.add(column.value().apply(sample)));
                Map<String, Object> custom = metadata.getOrDefault(sample.id, Map.of());
                active.forEach(field -> values.add(custom.get(field.key)));
                table.append(values);
            }
            Set<String> textKeys = active.stream().filter(field -> field.type == SampleMetadataFieldType.TEXT)
                    .map(field -> field.key).collect(Collectors.toSet());
            Sheet terms = workbook.createSheet("Vocabularies");
            Row header = terms.createRow(0);
            header.createCell(0).setCellValue("field_key");
            header.createCell(1).setCellValue("value");
            header.forEach(cell -> cell.setCellStyle(table.heading));
            terms.setColumnWidth(0, 30 * 256);
            terms.setColumnWidth(1, 40 * 256);
            terms.createFreezePane(0, 1);
            for (SampleVocabularyResponse vocabulary : vocabularies) {
                if (!vocabulary.custom() || !textKeys.contains(vocabulary.fieldKey())) continue;
                for (String value : vocabulary.terms()) {
                    table.checkSize(terms.getLastRowNum() + 1, 2);
                    Row row = terms.createRow(terms.getLastRowNum() + 1);
                    table.cell(row, 0, vocabulary.fieldKey());
                    table.cell(row, 1, value);
                }
            }
            return table.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export samples", e);
        }
    }

    public byte[] experiments(Assay assay, List<Sample> samples, List<FileResponse> files) {
        Map<UUID, List<FileResponse>> bySample = files.stream()
                .filter(file -> assay.id.equals(file.assayId()) && file.sampleId() != null)
                .collect(Collectors.groupingBy(FileResponse::sampleId));
        try (Workbook workbook = new XSSFWorkbook()) {
            ExportTable table = new ExportTable(workbook, "Experiments", EXPERIMENT_HEADERS);
            for (Sample sample : samples) {
                List<FileResponse> sampleFiles = bySample.getOrDefault(sample.id, List.of());
                if (sampleFiles.isEmpty()) table.append(experimentValues(assay, sample, null));
                // One row per file preserves multiple files of the same read role without guessing pairs.
                for (FileResponse file : sampleFiles) table.append(experimentValues(assay, sample, file));
            }
            return table.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to export experiments", e);
        }
    }

    private List<Object> experimentValues(Assay assay, Sample sample, FileResponse file) {
        List<Object> values = new ArrayList<>(Arrays.asList(sample.name, assay.studyAccession, assay.instrumentModel,
                assay.libraryName, assay.librarySource, assay.librarySelection, assay.libraryStrategy, assay.libraryLayout,
                assay.insertSize, assay.sequencingPlatform, assay.sequencingLaboratory));
        values.addAll(Collections.nCopies(9, null));
        if (file != null) {
            int offset = file.readRole() == ReadRole.FORWARD ? 14 : file.readRole() == ReadRole.REVERSE ? 17 : 11;
            values.set(offset, file.name());
            values.set(offset + 1, file.md5());
            values.set(offset + 2, file.unencryptedMd5());
        }
        return values;
    }

    private static final class ExportTable {
        private final Workbook workbook;
        private final Sheet sheet;
        private final int columns;
        private final CellStyle heading;
        private final CellStyle number;
        private long cells;

        ExportTable(Workbook workbook, String name, List<String> headers) {
            if (headers.size() > 511) throw new BadRequestException("Too many attributes for Excel export (maximum 511)");
            this.workbook = workbook;
            this.columns = headers.size();
            this.sheet = workbook.createSheet(name);
            heading = workbook.createCellStyle();
            heading.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            heading.setWrapText(true);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            heading.setFont(font);
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            number = workbook.createCellStyle();
            number.setDataFormat(workbook.createDataFormat().getFormat("General"));
            Row version = sheet.createRow(0);
            version.createCell(0).setCellValue(SpreadsheetImportSupport.VERSION_MARKER);
            version.createCell(1).setCellValue(SpreadsheetImportSupport.VERSION);
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("METADATA FIELDS:");
            for (int i = 0; i < headers.size(); i++) header.createCell(i + 1).setCellValue(headers.get(i));
            header.forEach(cell -> cell.setCellStyle(heading));
            header.setHeightInPoints(40);
            for (int i = 0; i <= columns; i++) {
                sheet.setColumnWidth(i, 24 * 256);
                sheet.setDefaultColumnStyle(i, text);
            }
            sheet.createFreezePane(1, 2);
        }

        void checkSize(int rowIndex, int columns) {
            cells += columns;
            if (rowIndex >= 100_000 || cells > 500_000) {
                throw new BadRequestException("Excel export is too large; select fewer samples (maximum 500,000 cells)");
            }
        }

        void append(List<Object> values) {
            int index = sheet.getLastRowNum() + 1;
            checkSize(index, columns + 1);
            Row row = sheet.createRow(index);
            for (int i = 0; i < values.size(); i++) cell(row, i + 1, values.get(i));
        }

        void cell(Row row, int column, Object value) {
            if (value == null) return;
            Cell cell = row.createCell(column);
            if (value instanceof Boolean bool) {
                cell.setCellValue(bool);
            } else if (value instanceof Number numeric && Double.isFinite(numeric.doubleValue())
                    && new BigDecimal(numeric.toString()).compareTo(
                            new BigDecimal(NumberToTextConverter.toText(numeric.doubleValue()))) == 0) {
                cell.setCellValue(numeric.doubleValue());
                cell.setCellStyle(number);
            } else {
                // Keep dates, identifiers, formula-like text and high precision decimals lossless as text.
                String text = value.toString();
                if (text.length() > 32767) throw new BadRequestException("A value exceeds Excel's 32,767 character cell limit");
                cell.setCellValue(text);
            }
        }

        byte[] finish() throws IOException {
            sheet.setAutoFilter(new CellRangeAddress(1, sheet.getLastRowNum(), 1, columns));
            try (var output = new ByteArrayOutputStream()) {
                workbook.write(output);
                if (output.size() > 20 * 1024 * 1024) {
                    throw new BadRequestException("Excel export exceeds the 20 MiB import limit; select fewer samples");
                }
                return output.toByteArray();
            }
        }
    }
}
