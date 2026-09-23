package com.engineeringmemory.knowledge.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.engineeringmemory.knowledge.dto.response.DocumentSummaryResponse;
import com.engineeringmemory.knowledge.entity.Document.IndexingStatus;
import com.engineeringmemory.knowledge.enums.DocumentType;

import tools.jackson.databind.ObjectMapper;

@Repository
public class DocumentListRepository {

	static final String SELECT_COLUMNS = """
			d.id, d.title, d.document_type,
			d.projects::text AS projects,
			d.technologies::text AS technologies,
			d.tags::text AS tags,
			d.source_name, d.media_type, d.occurred_on,
			d.version, d.embedding_model, d.indexing_status, d.created_at, d.updated_at
			""";
	static final String BASE_FROM_WHERE = " FROM document d WHERE d.owner_id = :ownerId\n";

	private final NamedParameterJdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	public DocumentListRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	public Page<DocumentSummaryResponse> search(long ownerId, String query,
			List<DocumentType> documentTypes, List<String> projects, List<String> technologies,
			List<String> tags, List<IndexingStatus> indexingStatuses,
			LocalDate from, LocalDate to, String currentEmbeddingModel, Pageable pageable) {
		StringBuilder fromWhere = new StringBuilder(BASE_FROM_WHERE);
		MapSqlParameterSource params = new MapSqlParameterSource("ownerId", ownerId);

		String normalizedQuery = nullIfBlank(query);
		if (normalizedQuery != null) {
			fromWhere.append("""
					 AND (lower(d.title) LIKE :query ESCAPE '\\'
					      OR lower(COALESCE(d.source_name, '')) LIKE :query ESCAPE '\\'
					      OR lower(d.content) LIKE :query ESCAPE '\\')
					""");
			params.addValue("query", "%" + escapeLike(normalizedQuery.toLowerCase(Locale.ROOT)) + "%");
		}
		if (documentTypes != null && !documentTypes.isEmpty()) {
			fromWhere.append(" AND d.document_type IN (:documentTypes)\n");
			params.addValue("documentTypes", documentTypes.stream().map(Enum::name).toList());
		}
		appendArrayFilter(fromWhere, params, "projects", "projects", projects);
		appendArrayFilter(fromWhere, params, "technologies", "technologies", technologies);
		appendArrayFilter(fromWhere, params, "tags", "tags", tags);
		if (indexingStatuses != null && !indexingStatuses.isEmpty()) {
			fromWhere.append(" AND d.indexing_status IN (:indexingStatuses)\n");
			params.addValue("indexingStatuses", indexingStatuses.stream().map(Enum::name).toList());
		}
		if (from != null) {
			fromWhere.append(" AND d.occurred_on >= :fromDate\n");
			params.addValue("fromDate", from);
		}
		if (to != null) {
			fromWhere.append(" AND d.occurred_on <= :toDate\n");
			params.addValue("toDate", to);
		}

		long total = jdbc.queryForObject("SELECT count(*)" + fromWhere, params, Long.class);
		if (total == 0) {
			return new PageImpl<>(List.of(), pageable, 0);
		}

		params.addValue("limit", pageable.getPageSize());
		params.addValue("offset", pageable.getOffset());
		String sql = "SELECT " + SELECT_COLUMNS + fromWhere
				+ " ORDER BY d.updated_at DESC, d.id DESC LIMIT :limit OFFSET :offset";
		List<DocumentSummaryResponse> content = jdbc.query(sql, params, (rs, rowNum) -> {
			DocumentType type = DocumentType.valueOf(rs.getString("document_type"));
			return new DocumentSummaryResponse(
					rs.getLong("id"), rs.getString("title"), type, type.getLabel(),
					readList(rs.getString("projects")),
					readList(rs.getString("technologies")),
					readList(rs.getString("tags")),
					rs.getString("source_name"), rs.getString("media_type"),
					rs.getObject("occurred_on", java.time.LocalDate.class),
					rs.getInt("version"), rs.getString("embedding_model"),
					IndexingStatus.valueOf(rs.getString("indexing_status")),
					!"READY".equals(rs.getString("indexing_status"))
							|| !java.util.Objects.equals(rs.getString("embedding_model"), currentEmbeddingModel),
					rs.getObject("created_at", java.time.OffsetDateTime.class),
					rs.getObject("updated_at", java.time.OffsetDateTime.class));
		});
		return new PageImpl<>(content, pageable, total);
	}

	private static void appendArrayFilter(StringBuilder sql, MapSqlParameterSource params,
			String column, String parameter, List<String> values) {
		if (values == null || values.isEmpty()) {
			return;
		}
		sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(d.")
				.append(column).append(") v(value) WHERE lower(v.value) IN (:")
				.append(parameter).append("))\n");
		params.addValue(parameter, values.stream()
				.map(String::strip)
				.map(value -> value.toLowerCase(Locale.ROOT))
				.distinct()
				.toList());
	}

	private List<String> readList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		return List.of(objectMapper.readValue(json, String[].class));
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
