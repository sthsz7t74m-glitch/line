create table if not exists conversation_contexts (
    line_user_id varchar(128) primary key references benly_users(line_user_id) on delete cascade,
    pending_action varchar(40) not null,
    expires_at timestamp with time zone not null,
    updated_at timestamp with time zone not null default current_timestamp
);

create index if not exists idx_conversation_contexts_expiry
    on conversation_contexts(expires_at);
