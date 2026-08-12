package br.com.quickfiller.infrastructure.storage;

import br.com.quickfiller.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryJobStore {
    private final ConcurrentHashMap<String, StoredJob> jobs = new ConcurrentHashMap<>();

    public void put(StoredJob job) { jobs.put(job.id(), job); }

    public StoredJob require(String id) {
        StoredJob job = jobs.get(id);
        if (job == null) throw new NotFoundException("transcrição não encontrada");
        return job;
    }

    public StoredJob remove(String id) { return jobs.remove(id); }

    public List<StoredJob> olderThan(Instant cutoff) {
        return jobs.values().stream().filter(job -> job.updatedAt().isBefore(cutoff)).toList();
    }
}
