create table insights_calendar (
    household_id bigint not null,
    user_id bigint not null,
    token varchar(36) not null unique,
    description varchar(16000) not null default '',
    run_date date,
    updated_at timestamp with time zone not null,
    primary key (household_id, user_id)
);
