package no.metatrack.server.admin;

import java.util.List;

public record AdminAuditPageResponse(List<AdminAuditResponse> events, int page, int size, long total) {
}