package no.metatrack.server.admin;

import java.util.List;

public record AdminProjectPageResponse(List<AdminProjectResponse> projects, int page, int size, long total) {
}