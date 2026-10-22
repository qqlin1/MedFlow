ALTER TABLE med_schedule
    ADD COLUMN cancel_reason VARCHAR(200) NULL COMMENT 'Administrative cancellation reason',
    ADD COLUMN cancelled_by BIGINT UNSIGNED NULL COMMENT 'Acting sys_user id',
    ADD COLUMN cancelled_at DATETIME(3) NULL COMMENT 'Database time after acquiring locks';
