alter table tasks add column if not exists reminder_minutes varchar(100) not null default '1440,60,0';
alter table tasks add column if not exists series_id varchar(64);
alter table tasks add column if not exists recurrence_label varchar(80);

create index if not exists idx_tasks_user_due
    on tasks(line_user_id, completed, due_at);
create index if not exists idx_tasks_series
    on tasks(line_user_id, series_id);
