package br.com.quickfiller.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum JobStatus {
    PROCESSING("processando"),
    COMPLETED("concluido"),
    ERROR("erro");

    private final String value;

    JobStatus(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }
}
