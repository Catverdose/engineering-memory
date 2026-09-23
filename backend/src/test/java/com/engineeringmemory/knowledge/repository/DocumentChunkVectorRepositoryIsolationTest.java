package com.engineeringmemory.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringmemory.common.exception.BusinessException;
import com.engineeringmemory.knowledge.dto.ChunkHit;
import com.engineeringmemory.knowledge.dto.SearchScope;
import com.engineeringmemory.knowledge.enums.DocumentType;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class DocumentChunkVectorRepositoryIsolationTest {

	private static final int DIMENSIONS = 1024;
	private static final String MODEL = "bge-m3";

	@Autowired
	private DocumentChunkVectorRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	private long ownerA;
	private long ownerB;
	private long documentA;
	private long documentB;
	private long chunkA;
	private long chunkB;

	private final float[] query = axis(0);

	@BeforeEach
	void setUp() {
		ownerA = insertUser("isolation-owner-a");
		ownerB = insertUser("isolation-owner-b");

		documentA = insertDocument(ownerA, "A 의 Nginx 장애 기록", "[\"ubot\"]", "[\"nginx\"]");
		documentB = insertDocument(ownerB, "B 의 Nginx 장애 기록", "[\"petcoupon\"]", "[\"nginx\"]");

		chunkA = insertChunk(documentA, ownerA, mix(0.6f, 0.8f));
		chunkB = insertChunk(documentB, ownerB, axis(0));
	}

	@Test
	@DisplayName("가장 가까운 이웃이 남의 자료여도 검색 결과에 섞이지 않는다")
	void searchNeverCrossesOwnerBoundary() {
		List<ChunkHit> hits = repository.search(ownerA, query, MODEL, SearchScope.all(), 10);

		assertThat(hits)
				.as("A 의 청크는 나와야 한다")
				.extracting(ChunkHit::chunkId)
				.containsExactly(chunkA);

		assertThat(hits)
				.as("B 의 문서가 어떤 형태로든 섞이면 안 된다")
				.noneMatch(hit -> hit.documentId() == documentB);

		List<ChunkHit> asOwnerB = repository.search(ownerB, query, MODEL, SearchScope.all(), 10);
		assertThat(asOwnerB).extracting(ChunkHit::chunkId).containsExactly(chunkB);
		assertThat(asOwnerB.get(0).similarity())
				.as("B 가 A 보다 가까워야 이 테스트가 의미를 갖는다")
				.isGreaterThan(hits.get(0).similarity());
	}

	@Test
	@DisplayName("범위 필터로는 소유자 경계를 넓힐 수 없다")
	void scopeCannotWidenBeyondOwner() {
		SearchScope othersProject = new SearchScope(
				List.of("petcoupon"), List.of(), Set.of(), null, null);

		assertThat(repository.search(ownerA, query, MODEL, othersProject, 10))
				.as("범위는 좁히는 장치다. 넓히는 데 쓰일 수 없어야 한다")
				.isEmpty();

		SearchScope sharedTech = new SearchScope(
				List.of(), List.of("nginx"), Set.of(DocumentType.TROUBLESHOOTING), null, null);

		assertThat(repository.search(ownerA, query, MODEL, sharedTech, 10))
				.extracting(ChunkHit::chunkId).containsExactly(chunkA);
		assertThat(repository.search(ownerB, query, MODEL, sharedTech, 10))
				.extracting(ChunkHit::chunkId).containsExactly(chunkB);
	}

	@Test
	@DisplayName("LIMIT 이 1이어도 남의 자료가 그 한 자리를 차지하지 않는다")
	void ownerFilterAppliesBeforeLimit() {
		assertThat(repository.search(ownerA, query, MODEL, SearchScope.all(), 1))
				.extracting(ChunkHit::chunkId)
				.containsExactly(chunkA);
	}

	@Test
	@DisplayName("남의 청크에는 벡터를 쓸 수 없다")
	void updateEmbeddingRefusesAnotherOwnersChunk() {
		assertThatThrownBy(() -> repository.updateEmbedding(chunkB, ownerA, 1, axis(1), MODEL))
				.isInstanceOf(BusinessException.class);

		repository.updateEmbedding(chunkA, ownerA, 1, axis(1), MODEL);
	}

	@Test
	@DisplayName("청크는 다른 소유자의 문서를 가리킬 수 없다 (복합 외래키)")
	void chunkCannotPointAtAnotherOwnersDocument() {
		assertThatThrownBy(() -> insertChunk(documentB, ownerA, axis(2)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertUser(String username) {
		return jdbc.queryForObject("""
				INSERT INTO app_user (username, password_hash, role, status)
				VALUES (?, '{noop}test', 'ADMIN', 'ACTIVE')
				RETURNING id
				""", Long.class, username + "-" + System.nanoTime());
	}

	private long insertDocument(long ownerId, String title, String projects, String technologies) {
		return jdbc.queryForObject("""
				INSERT INTO document (owner_id, title, document_type, projects, technologies, tags,
				                      source_name, media_type, original_content, occurred_on,
				                      content, content_hash, version, indexing_status)
				VALUES (?, ?, 'TROUBLESHOOTING', ?::jsonb, ?::jsonb, '[]'::jsonb,
				        'note.md', 'text/markdown', ?, ?,
				        ?, ?, 1, 'READY')
				RETURNING id
				""", Long.class,
				ownerId, title, projects, technologies,
				title.getBytes(StandardCharsets.UTF_8), LocalDate.of(2026, 1, 1),
				title, Long.toHexString(System.nanoTime()) + ownerId);
	}

	private long insertChunk(long documentId, long ownerId, float[] embedding) {
		return jdbc.queryForObject("""
				INSERT INTO document_chunk (document_id, owner_id, document_version, chunk_index,
				                            heading, content, char_count, embedding, embedding_model)
				VALUES (?, ?, 1, 0, 'buffering', 'proxy_buffering off 로 해결했다', 24,
				        CAST(? AS vector), ?)
				RETURNING id
				""", Long.class,
				documentId, ownerId, literal(embedding), MODEL);
	}

	private static float[] axis(int index) {
		float[] v = new float[DIMENSIONS];
		v[index] = 1.0f;
		return v;
	}

	private static float[] mix(float first, float second) {
		float[] v = new float[DIMENSIONS];
		v[0] = first;
		v[1] = second;
		return v;
	}

	private static String literal(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 4 + 2).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}
}
