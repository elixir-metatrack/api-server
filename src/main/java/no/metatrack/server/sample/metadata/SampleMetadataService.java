package no.metatrack.server.sample.metadata;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.sample.Sample;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class SampleMetadataService {
    @Transactional
    public void apply(Long projectId, Sample sample, Map<String, Object> metadata) {
        if (metadata == null) return;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            SampleMetadataField field = findActiveField(projectId, entry.getKey());
            SampleMetadataValue value = findValue(sample, field);
            if (entry.getValue() == null) {
                if (value != null) {
                    sample.metadataValues.remove(value);
                    value.delete();
                }
                continue;
            }
            if (value == null) {
                value = new SampleMetadataValue();
                value.sample = sample;
                value.field = field;
                sample.metadataValues.add(value);
            }
            setValue(value, field.type, entry.getValue());
            if (!value.isPersistent()) value.persist();
        }
    }

    public Map<String, Object> getActiveMetadata(Sample sample) {
        List<SampleMetadataValue> values = SampleMetadataValue.list(
                "sample.id = ?1 and field.archivedOn is null order by field.key", sample.id);
        Map<String, Object> result = new LinkedHashMap<>();
        for (SampleMetadataValue value : values) result.put(value.field.key, valueOf(value));
        return result;
    }

    public Map<UUID, Map<String, Object>> getActiveMetadata(List<Sample> samples) {
        Map<UUID, Map<String, Object>> result = new LinkedHashMap<>();
        if (samples.isEmpty()) return result;
        List<UUID> sampleIds = samples.stream().map(sample -> sample.id).toList();
        for (UUID sampleId : sampleIds) result.put(sampleId, new LinkedHashMap<>());
        List<SampleMetadataValue> values = SampleMetadataValue.list(
                "select value from SampleMetadataValue value join fetch value.field field "
                        + "where value.sample.id in ?1 and field.archivedOn is null order by value.sample.id, field.key",
                sampleIds);
        for (SampleMetadataValue value : values) {
            result.get(value.sample.id).put(value.field.key, valueOf(value));
        }
        return result;
    }

    public Object normalize(SampleMetadataFieldType type, Object rawValue) {
        if (rawValue == null) return null;
        return switch (type) {
            case TEXT -> {
                if (!(rawValue instanceof String text)) throw incompatible(type, rawValue);
                yield text;
            }
            case NUMBER -> normalizeNumber(rawValue);
            case BOOLEAN -> {
                if (!(rawValue instanceof Boolean bool)) throw incompatible(type, rawValue);
                yield bool;
            }
            case DATE -> normalizeDate(rawValue);
        };
    }

    public Object parseCsvValue(SampleMetadataFieldType type, String rawValue) {
        String value = rawValue == null ? null : rawValue.trim();
        if (value == null || value.isEmpty()) return null;
        try {
            return switch (type) {
                case TEXT -> value;
                case NUMBER -> new BigDecimal(value);
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new BadRequestException("Boolean custom metadata must be true or false");
                    }
                    yield Boolean.parseBoolean(value);
                }
                case DATE -> LocalDate.parse(value);
            };
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid custom metadata number '" + rawValue + "'", e);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Custom metadata date must use ISO format yyyy-MM-dd", e);
        }
    }

    private SampleMetadataField findActiveField(Long projectId, String key) {
        if (key == null) throw new BadRequestException("Custom metadata key must not be null");
        return SampleMetadataField.<SampleMetadataField>find(
                        "project.id = ?1 and key = ?2 and archivedOn is null", projectId, key)
                .firstResultOptional()
                .orElseThrow(() -> new BadRequestException("Unknown or archived custom metadata field '" + key + "'"));
    }

    private SampleMetadataValue findValue(Sample sample, SampleMetadataField field) {
        return SampleMetadataValue.<SampleMetadataValue>find("sample = ?1 and field = ?2", sample, field)
                .firstResultOptional().orElse(null);
    }

    private void setValue(SampleMetadataValue target, SampleMetadataFieldType type, Object rawValue) {
        target.textValue = null;
        target.numberValue = null;
        target.booleanValue = null;
        target.dateValue = null;
        Object normalized = normalize(type, rawValue);
        switch (type) {
            case TEXT -> target.textValue = (String) normalized;
            case NUMBER -> target.numberValue = (BigDecimal) normalized;
            case BOOLEAN -> target.booleanValue = (Boolean) normalized;
            case DATE -> target.dateValue = (LocalDate) normalized;
        }
    }

    private Object valueOf(SampleMetadataValue value) {
        return switch (value.field.type) {
            case TEXT -> value.textValue;
            case NUMBER -> value.numberValue;
            case BOOLEAN -> value.booleanValue;
            case DATE -> value.dateValue;
        };
    }

    private BigDecimal normalizeNumber(Object rawValue) {
        if (!(rawValue instanceof Number number)) throw incompatible(SampleMetadataFieldType.NUMBER, rawValue);
        if ((number instanceof Double d && !Double.isFinite(d)) || (number instanceof Float f && !Float.isFinite(f))) {
            throw new BadRequestException("Custom metadata number must be finite");
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid custom metadata number", e);
        }
    }

    private LocalDate normalizeDate(Object rawValue) {
        if (rawValue instanceof LocalDate date) return date;
        if (!(rawValue instanceof String text)) throw incompatible(SampleMetadataFieldType.DATE, rawValue);
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Custom metadata date must use ISO format yyyy-MM-dd", e);
        }
    }

    private BadRequestException incompatible(SampleMetadataFieldType type, Object rawValue) {
        return new BadRequestException("Value of type " + rawValue.getClass().getSimpleName() + " is incompatible with " + type);
    }
}