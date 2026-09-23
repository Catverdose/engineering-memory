package com.engineeringmemory.knowledge.dto.request;

import java.time.LocalDate;
import java.util.List;

import com.engineeringmemory.knowledge.enums.DocumentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentCreateRequest(

		@NotBlank(message = "title 은 필수입니다.")
		@Size(max = 300, message = "title 은 300자를 넘을 수 없습니다.")
		String title,

		@NotNull(message = "documentType 은 필수입니다.")
		DocumentType documentType,

		@Size(max = 20) List<@NotBlank @Size(max = 100) String> projects,
		@Size(max = 30) List<@NotBlank @Size(max = 100) String> technologies,
		@Size(max = 50) List<@NotBlank @Size(max = 100) String> tags,
		LocalDate occurredOn,
		@Size(max = 2000) String sourceUri,

		@NotBlank(message = "content 는 필수입니다.")
		String content) {

	public DocumentMetadataRequest metadata() {
		return new DocumentMetadataRequest(
				title, documentType, projects, technologies, tags, occurredOn, sourceUri);
	}
}
