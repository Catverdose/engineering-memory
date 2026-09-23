package com.engineeringmemory.knowledge.dto.request;

import java.time.LocalDate;
import java.util.List;

import com.engineeringmemory.knowledge.enums.DocumentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentMetadataRequest(

		@NotBlank(message = "title 은 필수입니다.")
		@Size(max = 300, message = "title 은 300자를 넘을 수 없습니다.")
		String title,

		@NotNull(message = "documentType 은 필수입니다.")
		DocumentType documentType,

		@Size(max = 20, message = "projects 는 최대 20개까지 지정할 수 있습니다.")
		List<@NotBlank @Size(max = 100) String> projects,

		@Size(max = 30, message = "technologies 는 최대 30개까지 지정할 수 있습니다.")
		List<@NotBlank @Size(max = 100) String> technologies,

		@Size(max = 50, message = "tags 는 최대 50개까지 지정할 수 있습니다.")
		List<@NotBlank @Size(max = 100) String> tags,

		LocalDate occurredOn,

		@Size(max = 2000, message = "sourceUri 는 2,000자를 넘을 수 없습니다.")
		String sourceUri) {
}
