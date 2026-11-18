-- ============================================================================================
-- V3: reference data.
--
-- Reference data the application needs to function belongs in a migration. Demo or test fixtures
-- do NOT - they belong in test setup or behind a profile (see StartupTour, which seeds only under
-- the `dev` profile).
--
-- `insert ... where not exists` keeps this re-runnable against a database where someone already
-- created the rows by hand. Flyway will not run it twice anyway, but the safety costs nothing.
-- ============================================================================================

insert into stock_item (sku, available, reserved, version)
select 'SKU-LAPTOP', 50, 0, 0
where not exists (select 1 from stock_item where sku = 'SKU-LAPTOP');

insert into stock_item (sku, available, reserved, version)
select 'SKU-MOUSE', 500, 0, 0
where not exists (select 1 from stock_item where sku = 'SKU-MOUSE');

insert into stock_item (sku, available, reserved, version)
select 'SKU-DOCK', 10, 0, 0
where not exists (select 1 from stock_item where sku = 'SKU-DOCK');
