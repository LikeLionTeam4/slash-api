package com.likelion.slash.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * TASK 프레임의 {@code payloadSha256} 계산.
 *
 * <p><b>무결성 증명이 아니다.</b> 정규화 알고리즘이 아직 확정되지 않아(지시문 11절)
 * 참조 구현({@code mock-api/src/taskOrchestrator.ts})과 같은 임시 방식을 그대로 따른다.
 * Agent 도 값을 다시 계산해 대조하지 않고 64자리 hex 형식만 본다.
 *
 * <p>알고리즘이 확정되면 이 클래스 하나만 고치면 된다.
 * 참조 구현과 어긋나면 나중에 대조를 켜는 순간 전체 전달이 거부되므로, 키 순서까지 맞춘다.
 */
final class AgentTaskPayloadHash {

    private AgentTaskPayloadHash() {
    }

    /**
     * 참조 구현의 {@code JSON.stringify({taskId, dispatchId, taskType, parameters})} 와 같은
     * 순서로 직렬화한 뒤 SHA-256 을 구한다.
     */
    static String of(ObjectMapper objectMapper,
                     UUID taskId,
                     UUID dispatchId,
                     String taskType,
                     JsonNode parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("dispatchId", dispatchId);
        payload.put("taskType", taskType);
        payload.put("parameters", parameters);

        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);

            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();

        } catch (Exception e) {
            // SHA-256 은 모든 JVM 이 갖고 있고 직렬화 대상도 우리가 만든 값이다.
            // 여기서 실패하면 환경 문제이므로 조용히 넘기지 않는다.
            throw new IllegalStateException("TASK 본문 해시 계산에 실패했습니다.", e);
        }
    }
}
