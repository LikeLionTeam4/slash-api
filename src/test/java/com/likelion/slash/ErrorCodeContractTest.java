package com.likelion.slash;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.error.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오류 코드가 계약 문서와 어긋나지 않는지 확인한다. (slash-web #21)
 *
 * <p>{@link ErrorCode} 와 {@code docs/frontend-api-contract.md} §4 표는 서로 다른 파일에
 * 손으로 적혀 있어 한쪽만 바뀌기 쉽다. {@code TaskTypeSchemaContractTest} 가 막는 것과 같은
 * 종류의 어긋남이다.
 *
 * <p><b>실제로 두 번 어긋났다.</b> {@code DEVICE_REVOKED}(#37)와 {@code LOCATION_NOT_FOUND}
 * (날씨 연결)를 코드에만 넣고 표에는 넣지 않았다. 프론트는 그 표를 보고 오류 코드 목록을
 * 만들기 때문에(slash-web #21 이 그렇게 대조했다) <b>표에 없으면 알 방법이 없다.</b>
 * 런타임에 터지지 않고 타입 차원에서만 드러나서 더 늦게 발견된다.
 */
class ErrorCodeContractTest {

    private static final Path CONTRACT = Path.of("docs/frontend-api-contract.md");

    /** §4 표의 각 줄은 {@code | `CODE` | HTTP | 설명 |} 모양이다. */
    private static final Pattern ROW = Pattern.compile("^\\| `([A-Z_]+)`", Pattern.MULTILINE);

    @Test
    @DisplayName("서버가 보내는 오류 코드는 모두 계약 문서에 적혀 있다")
    void 문서에_빠진_코드가_없다() throws IOException {
        Set<String> 문서 = 문서의_오류코드();
        Set<String> 코드 = Arrays.stream(ErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(코드)
                .as("코드에만 있고 문서에 없으면 프론트가 그 오류를 알 수 없다")
                .allSatisfy(name -> assertThat(문서).contains(name));
    }

    @Test
    @DisplayName("계약 문서에 적힌 오류 코드는 모두 서버에 있다")
    void 서버에_없는_코드를_적어_두지_않는다() throws IOException {
        Set<String> 코드 = Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

        // 없어진 코드를 문서에 남겨 두면 프론트가 오지 않을 분기를 만든다.
        assertThat(문서의_오류코드())
                .as("문서에만 있고 서버에 없으면 프론트가 쓸모없는 분기를 만든다")
                .allSatisfy(name -> assertThat(코드).contains(name));
    }

    /** §4 "오류 코드" 장의 표에서만 뽑는다. 본문 설명에 나온 코드는 목록이 아니다. */
    private Set<String> 문서의_오류코드() throws IOException {
        String 전체 = Files.readString(CONTRACT, StandardCharsets.UTF_8);

        int 시작 = 전체.indexOf("## 4. 오류 코드");
        int 끝 = 전체.indexOf("## 5. 인증");
        assertThat(시작).as("계약 문서에서 §4 를 찾지 못했다").isNotNegative();
        assertThat(끝).as("계약 문서에서 §5 를 찾지 못했다").isGreaterThan(시작);

        Matcher matcher = ROW.matcher(전체.substring(시작, 끝));
        Set<String> 찾은것 = new LinkedHashSet<>();
        while (matcher.find()) {
            찾은것.add(matcher.group(1));
        }
        return 찾은것;
    }
}
