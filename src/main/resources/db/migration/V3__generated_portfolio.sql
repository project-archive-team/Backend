-- 생성한 포트폴리오가 브라우저 상태에만 있어 새로고침하면 사라졌다.
-- 리포트 본문은 AI 응답 그대로(JSON) 보관한다 — 항목 구조가 바뀌어도 스키마를 따라 고치지 않아도 된다.
create table portfolio_reports (
    project_id   bigint      primary key references projects (id) on delete cascade,
    report       text        not null,
    generated_at timestamptz not null default now()
);
