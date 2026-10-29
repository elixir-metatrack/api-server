package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

public final class TableUploadSupport {
    private static final Set<String> TEXT_TYPES = Set.of(
            "text/csv", "text/plain", "text/tab-separated-values", "text/tsv", "application/vnd.ms-excel");

    private TableUploadSupport() {}

    public static boolean isWorkbook(File file) {
        if (file == null || !file.isFile()) throw new BadRequestException("No file uploaded");
        try {
            FileMagic magic = FileMagic.valueOf(file);
            return magic == FileMagic.OLE2 || magic == FileMagic.OOXML;
        } catch (IOException e) {
            throw new BadRequestException("Unable to read uploaded table", e);
        }
    }

    public static void validate(FileUpload upload) {
        if (upload == null) throw new BadRequestException("No file uploaded");
        String name = upload.fileName() == null ? "" : upload.fileName().toLowerCase(Locale.ROOT);
        boolean workbook = isWorkbook(upload.filePath().toFile());
        if (name.endsWith(".xlsm") || name.endsWith(".xlsb")) {
            throw new BadRequestException("Use CSV, TSV, XLS or XLSX files");
        }
        // Content detection is more reliable than browser-provided MIME types for Excel uploads.
        if (workbook) return;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw new BadRequestException("The file is not a valid XLS or XLSX workbook");
        }
        String type = upload.contentType();
        String baseType = type == null ? "" : type.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!TEXT_TYPES.contains(baseType)) throw new BadRequestException("File must be a CSV, TSV, XLS or XLSX file");
    }
}
