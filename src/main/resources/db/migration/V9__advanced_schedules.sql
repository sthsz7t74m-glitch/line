alter table schedules add column if not exists series_id varchar(64);
alter table schedules add column if not exists recurrence_label varchar(80);
alter table schedules add column if not exists reminder_minutes varchar(100) not null default '30';

create index if not exists idx_schedules_series on schedules(line_user_id, series_id);
create index if not exists idx_schedules_upcoming on schedules(starts_at);
