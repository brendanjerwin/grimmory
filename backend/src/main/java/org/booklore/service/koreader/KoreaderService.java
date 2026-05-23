package org.booklore.service.koreader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.*;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.util.koreader.EpubCfiService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class KoreaderService {

    private final UserBookProgressRepository progressRepository;
    private final UserBookFileProgressRepository fileProgressRepository;
    private final BookRepository bookRepository;
    private final BookFileRepository bookFileRepository;
    private final UserRepository userRepository;
    private final KoreaderUserRepository koreaderUserRepository;
    private final HardcoverSyncService hardcoverSyncService;
    private final EpubCfiService epubCfiService;

    public ResponseEntity<Map<String, String>> authorizeUser() {
        KoreaderUserDetails authDetails = getAuthDetails();
        KoreaderUserEntity koreaderUser = findKoreaderUser(authDetails.getUsername());
        validatePassword(koreaderUser, authDetails);

        log.info("User '{}' authorized", authDetails.getUsername());
        return ResponseEntity.ok(Map.of("username", authDetails.getUsername()));
    }

    public KoreaderProgress getProgress(String bookHash) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookFileMatch match = findKoreaderBookFileMatch(bookHash, authDetails.getBookLoreUserId());
        BookEntity book = match.book();
        UserBookProgressEntity progress = findUserProgress(authDetails.getBookLoreUserId(), book.getId());

        log.info("getProgress: fetched progress='{}' percentage={} for userId={} bookId={} bookFileId={}",
                progress.getKoreaderProgress(), progress.getKoreaderProgressPercent(),
                authDetails.getBookLoreUserId(), book.getId(), match.bookFile() != null ? match.bookFile().getId() : null);

        Long timestamp = progress.getKoreaderLastSyncTime() != null
                ? progress.getKoreaderLastSyncTime().getEpochSecond()
                : null;

        return KoreaderProgress.builder()
                .timestamp(timestamp)
                .document(bookHash)
                .progress(progress.getKoreaderProgress())
                .percentage(progress.getKoreaderProgressPercent())
                .device("BookLore")
                .device_id("BookLore")
                .build();
    }

    @Transactional
    public void saveProgress(String bookHash, KoreaderProgress koProgress) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookFileMatch match = findKoreaderBookFileMatch(bookHash, authDetails.getBookLoreUserId());
        BookEntity book = match.book();
        BookLoreUserEntity user = findBookLoreUser(authDetails.getBookLoreUserId());

        UserBookProgressEntity userProgress = getOrCreateUserProgress(user, book);
        Float previousProgressPercent = userProgress.getKoreaderProgressPercent();
        ReadStatus previousReadStatus = userProgress.getReadStatus();
        updateProgressData(userProgress, koProgress, authDetails.isSyncWithWebReader(), book, match.bookFile());

        progressRepository.save(userProgress);

        // Also save to file-level progress table (dual-write)
        saveToFileProgress(user, book, match.bookFile(), userProgress);

        log.info("saveProgress: saved progress='{}' percentage={} for userId={} bookId={} bookFileId={}",
                koProgress.getProgress(), koProgress.getPercentage(), authDetails.getBookLoreUserId(),
                book.getId(), match.bookFile() != null ? match.bookFile().getId() : null);

        // Sync progress to Hardcover asynchronously (if enabled for this user)
        // But only if the progress percentage has changed from last time, or the read status has changed
        if (koProgress.getPercentage() != null && (!koProgress.getPercentage().equals(previousProgressPercent)
                || userProgress.getReadStatus() != previousReadStatus)) {
            Float progressPercent = normalizeProgressPercent(koProgress.getPercentage());
            hardcoverSyncService.syncProgressToHardcover(book.getId(), progressPercent, authDetails.getBookLoreUserId());
        }
    }

    private void saveToFileProgress(BookLoreUserEntity user, BookEntity book, BookFileEntity matchedBookFile, UserBookProgressEntity progress) {
        try {
            BookFileEntity primaryFile = matchedBookFile != null ? matchedBookFile : book.getPrimaryBookFile();
            UserBookFileProgressEntity fileProgress = fileProgressRepository
                    .findByUserIdAndBookFileId(user.getId(), primaryFile.getId())
                    .orElseGet(UserBookFileProgressEntity::new);

            fileProgress.setUser(user);
            fileProgress.setBookFile(primaryFile);
            fileProgress.setLastReadTime(progress.getLastReadTime());

            // Map progress based on book type
            switch (primaryFile.getBookType()) {
                case EPUB, FB2, MOBI, AZW3 -> {
                    fileProgress.setPositionData(progress.getEpubProgress());
                    fileProgress.setPositionHref(progress.getEpubProgressHref());
                    fileProgress.setProgressPercent(progress.getEpubProgressPercent());
                }
                case PDF -> {
                    fileProgress.setPositionData(progress.getPdfProgress() != null ?
                            String.valueOf(progress.getPdfProgress()) : null);
                    fileProgress.setProgressPercent(progress.getPdfProgressPercent());
                }
                case CBX -> {
                    fileProgress.setPositionData(progress.getCbxProgress() != null ?
                            String.valueOf(progress.getCbxProgress()) : null);
                    fileProgress.setProgressPercent(progress.getCbxProgressPercent());
                }
            }

            fileProgressRepository.save(fileProgress);
        } catch (Exception e) {
            log.warn("Failed to save file-level progress for book {}: {}", book.getId(), e.getMessage());
        }
    }

    private void updateProgressData(UserBookProgressEntity userProgress, KoreaderProgress koProgress, boolean syncWithWebReader, BookEntity book, BookFileEntity matchedBookFile) {
        userProgress.setKoreaderProgress(koProgress.getProgress());
        userProgress.setKoreaderProgressPercent(koProgress.getPercentage());
        userProgress.setKoreaderDevice(koProgress.getDevice());
        userProgress.setKoreaderDeviceId(koProgress.getDevice_id());
        userProgress.setKoreaderLastSyncTime(Instant.now());
        userProgress.setLastReadTime(Instant.now());
        if (syncWithWebReader && koProgress.getProgress() != null) {
            try {
                BookFileEntity bookFile = matchedBookFile != null ? matchedBookFile : book.getPrimaryBookFile();
                String cfi = epubCfiService.convertXPointerToCfi(bookFile.getFullFilePath(), koProgress.getProgress());

                float percent = koProgress.getPercentage() * 100f;
                float rounded = BigDecimal
                        .valueOf(percent)
                        .setScale(1, RoundingMode.HALF_UP)
                        .floatValue();

                userProgress.setEpubProgress(cfi);
                userProgress.setEpubProgressPercent(rounded);

                log.info("Converted xpointer to CFI for BookLore reader sync: {}", cfi);
            } catch (Exception e) {
                log.warn("Failed to convert xpointer to CFI: {}", e.getMessage());
            }
        }

        updateReadStatus(userProgress, koProgress.getPercentage());
    }

    private void updateReadStatus(UserBookProgressEntity userProgress, Float progressFraction) {
        if (progressFraction == null) {
            return;
        }
        double progressPercent = progressFraction * 100.0;
        if (progressPercent >= 99.5) {
            userProgress.setReadStatus(ReadStatus.READ);
            userProgress.setDateFinished(Instant.now());
        } else if (progressPercent >= 0.25) {
            userProgress.setReadStatus(ReadStatus.READING);
        } else {
            userProgress.setReadStatus(ReadStatus.UNREAD);
        }
    }

    private Float normalizeProgressPercent(Float progress) {
        if (progress == null) {
            return null;
        }
        if (progress <= 1.0f) {
            return progress * 100.0f;
        }
        return progress;
    }

    private KoreaderUserDetails getAuthDetails() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof KoreaderUserDetails details)) {
            log.warn("Authentication failed: invalid principal type");
            throw ApiError.GENERIC_UNAUTHORIZED.createException("User not authenticated");
        }
        return details;
    }

    private KoreaderUserDetails getAuthDetailsWithSyncCheck() {
        KoreaderUserDetails authDetails = getAuthDetails();
        ensureSyncEnabled(authDetails);
        return authDetails;
    }

    private KoreaderUserEntity findKoreaderUser(String username) {
        return koreaderUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("KOReader user '{}' not found", username);
                    return ApiError.GENERIC_NOT_FOUND.createException("KOReader user not found");
                });
    }

    private void validatePassword(KoreaderUserEntity koreaderUser, KoreaderUserDetails authDetails) {
        if (koreaderUser.getPasswordMD5() == null ||
                !koreaderUser.getPasswordMD5().equalsIgnoreCase(authDetails.getPassword())) {
            log.warn("Password mismatch for user '{}'", authDetails.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Invalid credentials");
        }
    }

    private BookFileMatch findKoreaderBookFileMatch(String documentHash, long userId) {
        Optional<BookFileMatch> koreaderHashMatch = findBookFileByKoreaderDocumentHash(documentHash, userId);
        if (koreaderHashMatch.isPresent()) {
            return koreaderHashMatch.get();
        }

        Optional<BookEntity> currentHashMatch = bookRepository.findByCurrentHash(documentHash);
        if (currentHashMatch.isPresent()) {
            BookEntity book = currentHashMatch.get();
            log.debug("Resolved KOReader document by currentHash for bookId={}", book.getId());
            return new BookFileMatch(book, book.getPrimaryBookFile());
        }

        throw ApiError.GENERIC_NOT_FOUND.createException("Book not found for supplied KOReader document");
    }

    private Optional<BookFileMatch> findBookFileByKoreaderDocumentHash(String documentHash, long userId) {
        if (documentHash == null || !documentHash.matches("(?i)[a-f0-9]{32}")) {
            return Optional.empty();
        }

        List<BookFileEntity> matches = bookFileRepository.findAllByKoreaderHash(documentHash.toLowerCase());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            BookFileEntity bookFile = matches.getFirst();
            log.debug("Resolved KOReader document by koreaderHash for bookFileId={} bookId={}",
                    bookFile.getId(), bookFile.getBook().getId());
            return Optional.of(new BookFileMatch(bookFile.getBook(), bookFile));
        }

        Set<Long> candidateFileIds = matches.stream()
                .map(BookFileEntity::getId)
                .collect(Collectors.toSet());
        List<UserBookFileProgressEntity> fileProgressMatches = fileProgressRepository.findByUserIdAndBookFileIdIn(userId, candidateFileIds);
        if (fileProgressMatches.size() == 1) {
            BookFileEntity bookFile = fileProgressMatches.getFirst().getBookFile();
            log.debug("Resolved ambiguous KOReader hash by existing file progress for bookFileId={} bookId={}",
                    bookFile.getId(), bookFile.getBook().getId());
            return Optional.of(new BookFileMatch(bookFile.getBook(), bookFile));
        }

        Set<Long> candidateIds = matches.stream()
                .map(bookFile -> bookFile.getBook().getId())
                .collect(Collectors.toSet());
        Set<Long> progressBookIds = progressRepository.findExistingProgressBookIds(userId, candidateIds);
        if (progressBookIds.size() == 1) {
            Long preferredBookId = progressBookIds.iterator().next();
            List<BookFileEntity> matchingFilesForBook = matches.stream()
                    .filter(bookFile -> bookFile.getBook().getId().equals(preferredBookId))
                    .toList();
            if (matchingFilesForBook.size() == 1) {
                BookFileEntity bookFile = matchingFilesForBook.getFirst();
                log.debug("Resolved ambiguous KOReader hash by existing book progress for bookFileId={} bookId={}",
                        bookFile.getId(), bookFile.getBook().getId());
                return Optional.of(new BookFileMatch(bookFile.getBook(), bookFile));
            }
        }

        log.warn("Ambiguous KOReader hash matched bookFile IDs {} and book IDs {} for user {}", candidateFileIds, candidateIds, userId);
        throw ApiError.CONFLICT.createException("KOReader hash matches multiple books");
    }

    private record BookFileMatch(BookEntity book, BookFileEntity bookFile) {
    }

    private BookLoreUserEntity findBookLoreUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found with id " + userId));
    }

    private UserBookProgressEntity findUserProgress(long userId, Long bookId) {
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No progress found for user and book"));
    }

    private UserBookProgressEntity getOrCreateUserProgress(BookLoreUserEntity user, BookEntity book) {
        return progressRepository.findByUserIdAndBookId(user.getId(), book.getId())
                .orElseGet(() -> {
                    UserBookProgressEntity newProgress = new UserBookProgressEntity();
                    newProgress.setUser(user);
                    newProgress.setBook(book);
                    return newProgress;
                });
    }

    private void ensureSyncEnabled(KoreaderUserDetails details) {
        if (!details.isSyncEnabled()) {
            log.warn("Sync is disabled for user '{}'", details.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Sync is disabled for this user");
        }
    }
}
