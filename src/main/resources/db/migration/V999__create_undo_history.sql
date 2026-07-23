create table if not exists undo_history (
    id bigserial primary key,
    line_user_id varchar(255) not null,
    entity_type varchar(32) not null,
    entity_id bigint not null,
    action_type varchar(32) not null,
    snapshot_json text not null,
    description varchar(500),
    undone boolean not null default false,
    created_at timestamp not null default current_timestamp,
    undone_at timestamp
);

create index if not exists idx_undo_history_user_created
    on undo_history(line_user_id, created_at desc);
