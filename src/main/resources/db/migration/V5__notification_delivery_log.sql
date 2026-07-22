create table if not exists notification_delivery_log (
    id bigserial primary key,
    line_user_id varchar(255) not null,
    notification_type varchar(40) not null,
    target_key varchar(255) not null,
    sent_at timestamp not null default current_timestamp,
    unique(line_user_id, notification_type, target_key)
);
create index if not exists idx_notification_delivery_user_type
    on notification_delivery_log(line_user_id, notification_type, sent_at);
