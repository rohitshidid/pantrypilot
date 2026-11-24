create table insights_receipt_lot (
    household_id bigint not null,
    lot_id bigint not null,
    unit_price numeric(20,8),
    owner_id bigint,
    primary key (household_id, lot_id)
);
