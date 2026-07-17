package com.projectarchive.backend.collect;

import com.projectarchive.backend.domain.Source;

import java.util.List;

public interface Collector {

    Source.Type type();

    /**
     * @param accessToken 해당 provider의 복호화된 토큰. UPLOAD처럼 필요 없으면 null.
     * @throws RuntimeException 실패 시. SyncService가 잡아서 소스를 FAILED로 표시한다.
     */
    List<RawItem> collect(Source source, String accessToken);
}
