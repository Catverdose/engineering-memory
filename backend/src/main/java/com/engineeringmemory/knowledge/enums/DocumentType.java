package com.engineeringmemory.knowledge.enums;

public enum DocumentType {
	DOCUMENT("일반 문서"),

	PROJECT("프로젝트 자료"),

	TROUBLESHOOTING("장애·트러블슈팅"),

	TECH_DOC("기술 문서"),

	PROJECT_DOC("프로젝트 문서"),

	NOTE("개발·학습 노트"),

	RETROSPECTIVE("회고"),

	EXPERIMENT("실험·측정 결과"),

	LOG("로그"),

	DECISION("기술 선택 근거"),

	OTHER("기타");

	private final String label;

	DocumentType(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
