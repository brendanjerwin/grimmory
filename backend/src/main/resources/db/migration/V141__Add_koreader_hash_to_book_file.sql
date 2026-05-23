ALTER TABLE book_file
    ADD COLUMN koreader_hash VARCHAR(32);

CREATE INDEX idx_book_file_koreader_hash
    ON book_file (koreader_hash);
