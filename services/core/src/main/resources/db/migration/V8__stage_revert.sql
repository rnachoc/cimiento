-- A stage move can be undone by the person who made it (or management): the undo is a new row, never a delete.
ALTER TABLE tender_stage_change ADD COLUMN revert BOOLEAN NOT NULL DEFAULT false;
