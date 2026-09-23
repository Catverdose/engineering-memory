package com.engineeringmemory.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversationRenameRequest(
		@NotBlank @Size(max = 160) String title) {
}
