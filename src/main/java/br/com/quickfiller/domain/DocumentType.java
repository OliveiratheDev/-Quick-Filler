package br.com.quickfiller.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum DocumentType {
    TIMECARD("cartao-ponto"),
    PAYSLIP("holerite");

    private final String value;

    DocumentType(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static DocumentType from(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "tipo deve ser cartao-ponto ou holerite"));
    }
}
