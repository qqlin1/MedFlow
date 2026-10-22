-- Public appointment directory: no credentials or clinical patient data.
ALTER TABLE med_doctor
    ADD COLUMN title VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN specialty VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN introduction VARCHAR(4000) NOT NULL DEFAULT '';
