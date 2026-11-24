alter table insights_calendar add column google_enabled boolean not null default false;
create table insights_google_event (
    household_id bigint not null,
    user_id bigint not null,
    event_id varchar(32) not null,
    sent_date date,
    sent_description varchar(16000),
    primary key (household_id,user_id)
);
