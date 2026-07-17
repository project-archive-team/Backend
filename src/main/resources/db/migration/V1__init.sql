create table users (
    id            bigserial primary key,
    email         varchar(255) not null unique,
    password_hash varchar(255),
    name          varchar(255) not null,
    created_at    timestamptz  not null default now()
);

create table oauth_tokens (
    id            bigserial primary key,
    user_id       bigint       not null references users (id) on delete cascade,
    provider      varchar(16)  not null,
    access_token  varchar(4000) not null,
    refresh_token varchar(4000),
    expires_at    timestamptz,
    updated_at    timestamptz  not null default now(),
    unique (user_id, provider)
);

create table projects (
    id             bigserial primary key,
    owner_id       bigint       not null references users (id) on delete cascade,
    name           varchar(255) not null,
    period         varchar(255),
    member_count   int          not null default 1,
    status         varchar(16)  not null,
    created_at     timestamptz  not null default now(),
    last_synced_at timestamptz
);
create index idx_projects_owner on projects (owner_id);

create table project_tech_stack (
    project_id bigint       not null references projects (id) on delete cascade,
    tech       varchar(255) not null
);
create index idx_tech_project on project_tech_stack (project_id);

create table sources (
    id             bigserial primary key,
    project_id     bigint      not null references projects (id) on delete cascade,
    type           varchar(16) not null,
    external_ref   varchar(500),
    status         varchar(16) not null,
    message        varchar(1000),
    last_synced_at timestamptz,
    created_at     timestamptz not null default now(),
    unique (project_id, type, external_ref)
);
create index idx_sources_project on sources (project_id);

create table artifacts (
    id          bigserial primary key,
    project_id  bigint       not null references projects (id) on delete cascade,
    source_id   bigint       not null references sources (id) on delete cascade,
    type        varchar(16)  not null,
    external_id varchar(500) not null,
    title       varchar(500) not null,
    path        varchar(1000),
    content     text,
    author      varchar(255),
    occurred_at timestamptz,
    url         varchar(2000),
    indexed     boolean      not null default false,
    created_at  timestamptz  not null default now(),
    unique (source_id, external_id)
);
create index idx_artifacts_project on artifacts (project_id);
-- 타임라인은 이 인덱스로 프로젝트별 시간 역순 조회한다.
create index idx_artifacts_timeline on artifacts (project_id, occurred_at desc);
-- 인덱싱 대기분만 골라내는 경로.
create index idx_artifacts_unindexed on artifacts (project_id) where indexed = false;

create table chat_messages (
    id         bigserial primary key,
    project_id bigint      not null references projects (id) on delete cascade,
    role       varchar(16) not null,
    content    text        not null,
    citations  text,
    created_at timestamptz not null default now()
);
create index idx_chat_project on chat_messages (project_id, created_at);
