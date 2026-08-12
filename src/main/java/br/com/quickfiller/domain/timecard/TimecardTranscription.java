package br.com.quickfiller.domain.timecard;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TimecardTranscription(List<Page> pages) {
    public TimecardTranscription { pages = pages == null ? List.of() : List.copyOf(pages); }

    public record Page(int page, List<Day> days) {
        public Page { days = days == null ? List.of() : List.copyOf(days); }
    }

    public record Day(
            @JsonProperty("date_raw") String dateRaw,
            List<Punch> punches) {
        public Day {
            dateRaw = dateRaw == null ? "" : dateRaw;
            punches = punches == null ? List.of() : List.copyOf(punches);
        }
    }

    public record Punch(
            PunchKind kind,
            @JsonProperty("time_raw") String timeRaw,
            @JsonProperty("time_hhmm") String timeHhmm) {
        public Punch {
            timeRaw = timeRaw == null ? "" : timeRaw;
            timeHhmm = timeHhmm == null ? "" : timeHhmm;
        }
    }

    public enum PunchKind { IN, OUT }
}
