package no.metatrack.server.assay;

public record CSVExperimentRow(
        String sample,
        String fileMd5,
        String fileName,
        String fileUnencryptedMd5,
        String forwardFileMd5,
        String forwardFileName,
        String forwardFileUnencryptedMd5,
        Integer insertSize,
        String reverseFileMd5,
        String reverseFileName,
        String reverseFileUnencryptedMd5,
        String sequencingPlatform,
        String sequencingInstrument,
        String libraryName,
        String libraryLayout,
        String librarySelection,
        String librarySource,
        String libraryStrategy,
        String sequencingLaboratory) {
}