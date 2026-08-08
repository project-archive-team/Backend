-- 화면이 입력받고 보여주던 값들이 저장될 곳이 없어 새로고침마다 사라졌다. 컬럼을 만든다.

alter table users
    add column job_title varchar(255),
    add column bio       text,
    add column theme     varchar(16) not null default 'light';

create table user_tech_stack (
    user_id bigint       not null references users (id) on delete cascade,
    tech    varchar(255) not null
);
create index idx_user_tech on user_tech_stack (user_id);

alter table projects
    add column description text,
    add column role        varchar(255),
    add column category    varchar(255),
    -- 수집 상태(status)와 별개로, 사용자가 직접 고르는 진행/완료 구분.
    add column state       varchar(16) not null default 'ONGOING';

-- 직접 등록한 산출물에 붙는 태그. 수집기가 만든 아티팩트는 비어 있다.
create table artifact_tags (
    artifact_id bigint       not null references artifacts (id) on delete cascade,
    tag         varchar(100) not null
);
create index idx_artifact_tags on artifact_tags (artifact_id);
