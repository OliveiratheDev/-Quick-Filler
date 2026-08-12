package br.com.quickfiller.domain.payslip;

import java.util.List;

public record PayslipTranscription(List<Page> pages) {
    public PayslipTranscription { pages = pages == null ? List.of() : List.copyOf(pages); }

    public record Page(int page, String year, String month, List<Field> fields, List<Base> bases) {
        public Page {
            year = year == null ? "" : year;
            month = month == null ? "" : month;
            fields = fields == null ? List.of() : List.copyOf(fields);
            bases = bases == null ? List.of() : List.copyOf(bases);
        }
    }

    public record Field(String code, String label, String reference, String value) {
        public Field {
            code = code == null ? "" : code;
            label = label == null ? "" : label;
            reference = reference == null ? "" : reference;
            value = value == null ? "" : value;
        }
    }

    public record Base(String label, String value) {
        public Base {
            label = label == null ? "" : label;
            value = value == null ? "" : value;
        }
    }
}
