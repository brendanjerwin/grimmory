package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.migration.Migration;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PopulateKoreaderHashesMigration implements Migration {

    private static final int BATCH_SIZE = 100;

    private final BookFileRepository bookFileRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public String getKey() {
        return "populateKoreaderHashesV1";
    }

    @Override
    public String getDescription() {
        return "Calculate and store KOReader full-file MD5 hashes for book files";
    }

    @Override
    public void execute() {
        log.info("Starting migration: {}", getKey());

        long afterId = 0L;
        int updated = 0;
        int skipped = 0;
        List<String> skippedExamples = new ArrayList<>();

        while (true) {
            List<BookFileEntity> files = fetchBatch(afterId);
            if (files.isEmpty()) {
                break;
            }

            for (BookFileEntity bookFile : files) {
                afterId = bookFile.getId();
                try {
                    Path path = bookFile.getFullFilePath();
                    bookFile.setKoreaderHash(FileFingerprint.generateFullFileMd5(path));
                    updated++;
                } catch (RuntimeException e) {
                    skipped++;
                    if (skippedExamples.size() < 5) {
                        skippedExamples.add("bookFileId=" + bookFile.getId() + " error=" + e.getMessage());
                    }
                    log.debug("Skipping KOReader hash for bookFileId={}: {}", bookFile.getId(), e.getMessage());
                }
            }

            saveBatch(files);
        }

        log.info("Migration '{}' populated {} KOReader hashes; skipped {} files.", getKey(), updated, skipped);
        if (skipped > 0) {
            log.warn("Migration '{}' skipped {} files. Examples: {}", getKey(), skipped, skippedExamples);
            throw new IllegalStateException("KOReader hash backfill skipped " + skipped + " files");
        }
    }

    private List<BookFileEntity> fetchBatch(long afterId) {
        return transactionTemplate.execute(status ->
                bookFileRepository.findActiveBookFilesMissingKoreaderHashAfterId(afterId, PageRequest.of(0, BATCH_SIZE)));
    }

    private void saveBatch(List<BookFileEntity> files) {
        transactionTemplate.executeWithoutResult(status -> bookFileRepository.saveAll(files));
    }
}
