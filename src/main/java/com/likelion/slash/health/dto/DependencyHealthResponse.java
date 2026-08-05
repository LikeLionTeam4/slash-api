package com.likelion.slash.health.dto;

import com.likelion.slash.common.SlashTime;
import java.time.OffsetDateTime;

/**
 * 의존 서비스 연결 상태.
 *
 * @param database  RDS PostgreSQL 연결 상태
 * @param valkey    ElastiCache for Valkey 연결 상태
 * @param checkedAt 점검 시각 (한국 시각)
 */
public record DependencyHealthResponse(
        Status database,
        Status valkey,
        OffsetDateTime checkedAt) {

    public enum Status { UP, DOWN }

    public static DependencyHealthResponse of(Status database, Status valkey) {
        return new DependencyHealthResponse(database, valkey, SlashTime.now());
    }

    /** 모든 의존 서비스가 정상인지 확인한다. */
    public boolean allUp() {
        return database == Status.UP && valkey == Status.UP;
    }
}
