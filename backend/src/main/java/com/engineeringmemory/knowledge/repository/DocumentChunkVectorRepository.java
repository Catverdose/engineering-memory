package com.engineeringmemory.knowledge.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.engineeringmemory.aiconfig.config.OllamaProperties;
import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.common.exception.ErrorCode;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.knowledge.enums.DocumentType;

import tools.jackson.databind.ObjectMapper;

@Repository
public class DocumentChunkVectorRepository {

	static final String BASE_SEARCH_SQL = """
			SELECT c.id AS chunk_id,
			       c.document_id,
			       d.title,
			       d.document_type,
			       d.projects::text AS projects,
			       d.technologies::text AS technologies,
			       d.tags::text AS tags,
			       d.occurred_on,
			       c.chunk_index,
			       c.heading,
			       c.content,
			       1 - (c.embedding <=> CAST(:queryVector AS vector)) AS similarity
			  FROM document_chunk c
			  JOIN document d
			    ON d.id = c.document_id
			   AND d.owner_id = c.owner_id
			 WHERE c.owner_id = :ownerId
			   AND d.owner_id = :ownerId
			   AND d.indexing_status = 'READY'
			   AND c.document_version = d.version
			   AND c.embedding IS NOT NULL
			   AND c.embedding_model = :embeddingModel
			""";

	private static final String UPDATE_VECTOR_SQL = """
			UPDATE document_chunk
			   SET embedding = CAST(? AS vector),
			       embedding_model = ?
			 WHERE id = ?
			   AND owner_id = ?
			   AND document_version = ?
			""";

	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedJdbcTemplate;
	private final ObjectMapper objectMapper;
	private final int embeddingDimensions;

	public DocumentChunkVectorRepository(
			JdbcTemplate jdbcTemplate,
			ObjectMapper objectMapper,
			OllamaProperties ollamaProperties) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.objectMapper = objectMapper;
		this.embeddingDimensions = ollamaProperties.embeddingDimensions();
	}

	public void updateEmbedding(long chunkId, long ownerId, int documentVersion,
			float[] embedding, String embeddingModel) {
		String literal = toVectorLiteral(embedding);
		int updated = jdbcTemplate.update(UPDATE_VECTOR_SQL,
				literal, embeddingModel, chunkId, ownerId, documentVersion);
		if (updated != 1) {
			throw new BusinessException(ErrorCode.INVARIANT_VIOLATION,
					"벡터를 저장할 청크가 없거나 소유자·버전이 일치하지 않습니다. chunkId=" + chunkId);
		}
	}

	public List<ChunkHit> search(long ownerId, float[] queryVector, String embeddingModel,
			SearchScope scope, int limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("limit 은 1 이상이어야 합니다.");
		}
		if (embeddingModel == null || embeddingModel.isBlank()) {
			throw new IllegalArgumentException("embeddingModel 은 비어 있을 수 없습니다.");
		}
		SearchScope effectiveScope = scope == null ? SearchScope.all() : scope;
		String vector = toVectorLiteral(queryVector);

		StringBuilder sql = new StringBuilder(BASE_SEARCH_SQL);
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("ownerId", ownerId)
				.addValue("queryVector", vector)
				.addValue("embeddingModel", embeddingModel)
				.addValue("resultLimit", limit);

		if (!effectiveScope.projects().isEmpty()) {
			sql.append("""
					   AND EXISTS (
					       SELECT 1 FROM jsonb_array_elements_text(d.projects) p(value)
					        WHERE lower(p.value) IN (:projects)
					   )
					""");
			parameters.addValue("projects", effectiveScope.projects());
		}
		if (!effectiveScope.technologies().isEmpty()) {
			sql.append("""
					   AND EXISTS (
					       SELECT 1 FROM jsonb_array_elements_text(d.technologies) t(value)
					        WHERE lower(t.value) IN (:technologies)
					   )
					""");
			parameters.addValue("technologies", effectiveScope.technologies());
		}
		if (!effectiveScope.documentTypes().isEmpty()) {
			sql.append("   AND d.document_type IN (:documentTypes)\n");
			parameters.addValue("documentTypes",
					effectiveScope.documentTypes().stream().map(Enum::name).toList());
		}
		if (effectiveScope.from() != null) {
			sql.append("   AND d.occurred_on >= :fromDate\n");
			parameters.addValue("fromDate", effectiveScope.from());
		}
		if (effectiveScope.to() != null) {
			sql.append("   AND d.occurred_on <= :toDate\n");
			parameters.addValue("toDate", effectiveScope.to());
		}

		sql.append(" ORDER BY c.embedding <=> CAST(:queryVector AS vector)\n");
		sql.append(" LIMIT :resultLimit");
		return namedJdbcTemplate.query(sql.toString(), parameters, rowMapper());
	}

	private RowMapper<ChunkHit> rowMapper() {
		return (rs, rowNum) -> new ChunkHit(
				rs.getLong("chunk_id"),
				rs.getLong("document_id"),
				rs.getString("title"),
				DocumentType.valueOf(rs.getString("document_type")),
				readStringList(rs.getString("projects")),
				readStringList(rs.getString("technologies")),
				readStringList(rs.getString("tags")),
				rs.getObject("occurred_on", java.time.LocalDate.class),
				rs.getInt("chunk_index"),
				rs.getString("heading"),
				rs.getString("content"),
				rs.getDouble("similarity"));
	}

	private List<String> readStringList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		String[] values = objectMapper.readValue(json, String[].class);
		return List.of(values);
	}

	String toVectorLiteral(float[] vector) {
		if (vector == null || vector.length != embeddingDimensions) {
			throw new IllegalArgumentException(
					"임베딩 차원이 %d 이어야 하는데 %s 입니다."
							.formatted(embeddingDimensions, vector == null ? "null" : vector.length));
		}
		StringBuilder literal = new StringBuilder(vector.length * 12 + 2).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (!Float.isFinite(vector[i])) {
				throw new IllegalArgumentException("임베딩에는 NaN 또는 Infinity를 포함할 수 없습니다.");
			}
			if (i > 0) {
				literal.append(',');
			}
			literal.append(vector[i]);
		}
		return literal.append(']').toString();
	}
}
