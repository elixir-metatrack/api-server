package no.metatrack.server.isa;

import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Field register shared by ISA-JSON export and import (docs/isa-json.md).
 * ISA names follow the ENA/BioSamples checklist labels where one exists, otherwise the Metatrack key.
 */
public final class IsaSampleFields {
    /** {@code exported=false}: accepted on import, never written (e.g. patient references). */
    public record Field(String property, String isaName, List<String> aliases, boolean exported,
                        Function<SampleResponse, Object> getter, BiConsumer<Sample, String> setter) {}

    public static final String ORGANISM = "organism";
    public static final String HOST_SCIENTIFIC_NAME = "host scientific name";

    public static final List<Field> FIELDS = List.of(
            field("taxId", "tax_id", List.of("tax id", "taxid", "ncbi taxon id"), SampleResponse::taxId,
                    (s, v) -> s.taxId = taxId(v)),
            field("hostTaxId", "host taxid", List.of("host tax id", "host_tax_id"), SampleResponse::hostTaxId,
                    (s, v) -> s.hostTaxId = taxId(v)),
            field("collectionDate", "collection date", List.of(), SampleResponse::collectionDate,
                    (s, v) -> s.collectionDate = LocalDate.parse(v)),
            field("location", "geographic location (country and/or sea)",
                    List.of("country", "geo_loc_name", "geographic location"), SampleResponse::location,
                    (s, v) -> s.location = v),
            field("county", "county", List.of(), SampleResponse::county, (s, v) -> s.county = v),
            field("commune", "commune", List.of(), SampleResponse::commune, (s, v) -> s.commune = v),
            field("latitude", "geographic location (latitude)", List.of("latitude"), SampleResponse::latitude,
                    (s, v) -> s.latitude = Double.valueOf(v)),
            field("longitude", "geographic location (longitude)", List.of("longitude"), SampleResponse::longitude,
                    (s, v) -> s.longitude = Double.valueOf(v)),
            field("isolationSource", "isolation_source", List.of(), SampleResponse::isolationSource,
                    (s, v) -> s.isolationSource = v),
            field("isolationSourceHostAssociated", "isolation source host-associated", List.of(),
                    SampleResponse::isolationSourceHostAssociated, (s, v) -> s.isolationSourceHostAssociated = v),
            field("isolationSourceNonHostAssociated", "isolation source non-host-associated", List.of(),
                    SampleResponse::isolationSourceNonHostAssociated,
                    (s, v) -> s.isolationSourceNonHostAssociated = v),
            field("isolate", "isolate", List.of(), SampleResponse::isolate, (s, v) -> s.isolate = v),
            field("strain", "strain", List.of(), SampleResponse::strain, (s, v) -> s.strain = v),
            field("serovar", "serovar", List.of(), SampleResponse::serovar, (s, v) -> s.serovar = v),
            field("collectedBy", "collected_by", List.of(), SampleResponse::collectedBy, (s, v) -> s.collectedBy = v),
            field("collectorName", "collector name", List.of(), SampleResponse::collectorName,
                    (s, v) -> s.collectorName = v),
            field("collectingInstitution", "collecting institution", List.of(), SampleResponse::collectingInstitution,
                    (s, v) -> s.collectingInstitution = v),
            field("environmentalSample", "environmental_sample", List.of(), SampleResponse::environmentalSample,
                    (s, v) -> s.environmentalSample = v),
            field("hostAssociated", "Is the sequenced pathogen host associated?", List.of("host associated"),
                    SampleResponse::hostAssociated, (s, v) -> s.hostAssociated = v),
            field("hostHealthState", "host health state", List.of(), SampleResponse::hostHealthState,
                    (s, v) -> s.hostHealthState = v),
            field("hostCommonName", "host common name", List.of(), SampleResponse::hostCommonName,
                    (s, v) -> s.hostCommonName = v),
            new Field("hostSubjectId", "host subject id", List.of(), false, SampleResponse::hostSubjectId,
                    (s, v) -> s.hostSubjectId = v),
            field("hostSex", "host sex", List.of(), SampleResponse::hostSex, (s, v) -> s.hostSex = v),
            field("hostAge", "host age", List.of(), SampleResponse::hostAge, (s, v) -> s.hostAge = v),
            field("hostHabitat", "host habitat", List.of(), SampleResponse::hostHabitat, (s, v) -> s.hostHabitat = v),
            field("hostBehaviour", "host behaviour", List.of(), SampleResponse::hostBehaviour,
                    (s, v) -> s.hostBehaviour = v),
            field("influenzaTestMethod", "influenza test method", List.of(), SampleResponse::influenzaTestMethod,
                    (s, v) -> s.influenzaTestMethod = v),
            field("influenzaTestResult", "influenza test result", List.of(), SampleResponse::influenzaTestResult,
                    (s, v) -> s.influenzaTestResult = v),
            field("otherPathogensTested", "other pathogens tested", List.of(), SampleResponse::otherPathogensTested,
                    (s, v) -> s.otherPathogensTested = v),
            field("otherPathogensTestResult", "other pathogens test result", List.of(),
                    SampleResponse::otherPathogensTestResult, (s, v) -> s.otherPathogensTestResult = v),
            field("influenzaVirusType", "influenza virus type", List.of(), SampleResponse::influenzaVirusType,
                    (s, v) -> s.influenzaVirusType = v),
            field("influenzaSubType", "influenza subtype", List.of("influenza sub type"),
                    SampleResponse::influenzaSubType, (s, v) -> s.influenzaSubType = v),
            field("projectTitle", "title", List.of("sample title", "project title"), SampleResponse::projectTitle,
                    (s, v) -> s.projectTitle = v),
            field("description", "description", List.of("sample description"), SampleResponse::description,
                    (s, v) -> s.description = v),
            field("alias", "alias", List.of(), SampleResponse::alias, (s, v) -> s.alias = v),
            // MARS uses a characteristic named exactly "accession" as the BioSamples accession of the sample.
            field("sampleAccession", "accession", List.of("sample accession", "biosample accession",
                    "biosamples accession"), SampleResponse::sampleAccession, (s, v) -> s.sampleAccession = v),
            field("secondaryAccession", "secondary accession", List.of(), SampleResponse::secondaryAccession,
                    (s, v) -> s.secondaryAccession = v),
            field("projectAccession", "project accession", List.of(), SampleResponse::projectAccession,
                    (s, v) -> s.projectAccession = v),
            field("studyAccession", "study accession", List.of(), SampleResponse::studyAccession,
                    (s, v) -> s.studyAccession = v),
            field("mlst", "mlst", List.of(), SampleResponse::mlst, (s, v) -> s.mlst = v),
            field("sequencingLab", "sequencing lab", List.of(), SampleResponse::sequencingLab,
                    (s, v) -> s.sequencingLab = v),
            field("institution", "institution", List.of(), SampleResponse::institution, (s, v) -> s.institution = v),
            field("hospitalHealthInstitution", "hospital health institution", List.of("hospital/health institution"),
                    SampleResponse::hospitalHealthInstitution, (s, v) -> s.hospitalHealthInstitution = v));

    private static final Map<String, Field> BY_NAME = FIELDS.stream()
            .flatMap(field -> Stream.concat(Stream.of(field.isaName(), field.property()), field.aliases().stream())
                    .map(name -> Map.entry(normalize(name), field)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));

    private static final Pattern TAX_ID = Pattern.compile(
            "(?i)(?:.*?(?:txid|NCBITaxon[_:]))?(\\d+)");

    private IsaSampleFields() {}

    /** Accepts "287", "NCBI:txid287", "txid287", "NCBITaxon:287" and "NCBITaxon_287". */
    public static Integer taxId(String value) {
        Matcher matcher = TAX_ID.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Not an NCBI taxonomy ID: " + value);
        return Integer.valueOf(matcher.group(1));
    }

    public static Optional<Field> find(String isaName) {
        return Optional.ofNullable(BY_NAME.get(normalize(isaName)));
    }

    public static boolean isOrganism(String isaName) {
        String name = normalize(isaName);
        return name.equals("organism") || name.equals("scientific name") || name.equals("taxon");
    }

    public static boolean isHostOrganism(String isaName) {
        String name = normalize(isaName);
        return name.equals("host scientific name") || name.equals("host");
    }

    /** Case-insensitive; spaces, underscores and hyphens are treated as equal. */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
    }

    private static Field field(String property, String isaName, List<String> aliases,
                               Function<SampleResponse, Object> getter, BiConsumer<Sample, String> setter) {
        return new Field(property, isaName, aliases, true, getter, setter);
    }
}
