DELETE FROM global_sample_vocabulary WHERE field_key = 'mlst';

DELETE FROM sample_vocabulary WHERE field_key = 'mlst';

ALTER TABLE sample DROP COLUMN mlst;