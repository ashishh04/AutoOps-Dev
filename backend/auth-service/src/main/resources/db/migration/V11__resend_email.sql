-- Email provider switched from SendGrid to Resend.
--
-- The column held SendGrid's X-Message-Id and is now the Resend id; nothing
-- about its shape changes (both fit comfortably in 128 chars), so this is a
-- rename rather than a migration of data. Renaming rather than leaving it is
-- the point: a column called sg_message_id holding a Resend id is the kind of
-- small lie that costs somebody an hour in two years.
--
-- Existing rows keep their old SendGrid ids. Those ids will never be matched
-- by a webhook again, which is correct — SendGrid will not be posting.

ALTER TABLE otp_entries
    CHANGE COLUMN sendgrid_message_id provider_message_id VARCHAR(128) NULL;

-- CHANGE COLUMN carries the index across but keeps its old name, which would
-- leave the vendor's name in the schema anyway.
ALTER TABLE otp_entries
    RENAME INDEX idx_otp_sendgrid_msg TO idx_otp_provider_msg;
