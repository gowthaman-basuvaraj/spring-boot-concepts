-- ============================================================================================
-- V2: indexes.
--
-- In their own migration because index creation is the part most likely to need
-- `create index concurrently` (PostgreSQL) on a large table, which cannot run inside a
-- transaction - and you do not want that mixed in with table definitions.
--
-- Every index below exists because a specific query needs it. An index nobody queries is pure
-- write amplification.
-- ============================================================================================

-- findByOrderNumber / findWithLinesByOrderNumber are served by the unique constraint from V1:
-- a unique constraint IS an index, so no extra one is needed.

-- OrderSpecifications.customerEmailContains and findRecentForCustomer.
create index idx_orders_customer_email on orders (customer_email);

-- hasStatus combined with the default sort on created_at. A COMPOSITE index ordered
-- (equality column, range/sort column) serves the filter AND the ORDER BY - the difference
-- between an index scan and sorting the whole result set.
create index idx_orders_status_created_at on orders (status, created_at desc);

-- The FK column. PostgreSQL does NOT index foreign keys automatically, and an unindexed FK makes
-- every parent DELETE scan the child table, and take stronger locks while doing it.
create index idx_order_line_order_id on order_line (order_id);

-- OrderSpecifications.containsSku joins order_line on sku.
create index idx_order_line_sku on order_line (sku);

create index idx_payment_attempt_order_number on payment_attempt (order_number);

-- The outbox poller's query: unpublished, oldest first. On PostgreSQL a PARTIAL index stays tiny
-- no matter how many published rows accumulate:
--   create index idx_outbox_unpublished on outbox_message (id) where published_at is null;
-- H2 has no partial indexes, so the portable composite form is used here.
create index idx_outbox_unpublished on outbox_message (published_at, id);

create index idx_tx_note_label on tx_note (label);
