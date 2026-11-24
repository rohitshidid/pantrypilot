create table insights_threshold (
    household_id bigint not null,
    ingredient_id bigint not null,
    name varchar(150) not null,
    unit varchar(8) not null,
    minimum numeric(20,6) not null check (minimum > 0),
    primary key (household_id, ingredient_id)
);
