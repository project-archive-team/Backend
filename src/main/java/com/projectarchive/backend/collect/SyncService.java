package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.Project;
import com.projectarchive.backend.domain.Source;
import com.projectarchive.backend.repo.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 프로젝트 전체 동기화. 별도 스레드에서 돌고, 진행 상황은 sources.status로 폴링한다.
 * ponytail: 큐(Redis/SQS) 대신 @Async. 재시작 중 유실이 문제가 되거나 워커를 분리해야 하면 그때 큐로.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncService {

    private final SourceRepository sources;
    private final SourceSyncer syncer;

    @Async("syncExecutor")
    public void syncProject(Long projectId) {
        syncer.markProject(projectId, Project.Status.ANALYZING, false);
        try {
            List<Source> targets = sources.findByProjectId(projectId);
            for (Source s : targets) {
                // syncSource가 예외를 자체적으로 삼키므로 한 소스 실패가 루프를 끊지 않는다.
                syncer.syncSource(s.getId());
            }
            syncer.indexPending(projectId);
            syncer.markProject(projectId, Project.Status.DONE, true);
        } catch (Exception e) {
            log.error("project {} sync failed", projectId, e);
            syncer.markProject(projectId, Project.Status.PENDING, false);
        }
    }
}
