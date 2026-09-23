package com.engineeringmemory.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(

		@NotBlank(message = "아이디를 입력해 주세요.")
		@Size(max = 100, message = "아이디는 100자를 넘을 수 없습니다.")
		@Pattern(regexp = "^[\\p{L}\\p{N}._@-]+$", message = "아이디 형식이 올바르지 않습니다.")
		String username,

		@NotBlank(message = "비밀번호를 입력해 주세요.")
		@Size(max = 256, message = "비밀번호는 256자를 넘을 수 없습니다.")
		String password) {
}
