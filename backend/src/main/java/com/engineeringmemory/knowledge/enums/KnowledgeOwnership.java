package com.engineeringmemory.knowledge.enums;

public enum KnowledgeOwnership {

	MINE(" FROM document d WHERE d.owner_id = :ownerId\n"),

	SHARED(" FROM document d WHERE d.owner_id IS NULL\n"),

	ALL(" FROM document d WHERE (d.owner_id = :ownerId OR d.owner_id IS NULL)\n");

	private final String fromWhere;

	KnowledgeOwnership(String fromWhere) {
		this.fromWhere = fromWhere;
	}

	public String fromWhere() {
		return fromWhere;
	}
}
