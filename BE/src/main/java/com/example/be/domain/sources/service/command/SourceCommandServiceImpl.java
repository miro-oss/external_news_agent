package com.example.be.domain.sources.service.command;

import com.example.be.domain.sources.converter.SourceConverter;
import com.example.be.domain.sources.dto.req.SourceReqDTO;
import com.example.be.domain.sources.dto.res.SourceResDTO;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.SearchProvider;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.robots.RobotsPolicyService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.exception.SourceException;
import com.example.be.domain.sources.exception.code.SourceErrorCode;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import com.example.be.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class SourceCommandServiceImpl implements SourceCommandService {

    private static final String SOURCE_UNIQUE_CONSTRAINT = "UQ_NEWS_SOURCE";
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    private final SourceRepository sourceRepository;
    private final RobotsPolicyService robotsPolicyService;

    @Override
    public SourceResDTO.Created createSource(SourceReqDTO.Create request) {
        Source source = SourceConverter.toSource(request);

        validateKind(source.getSourceKind());
        validateName(source.getName());
        validateUrlTemplate(source.getSourceKind(), source.getUrlTemplate());
        validateCountryAndLanguage(source.getCountry(), source.getLanguage());
        validateReliabilityScore(source.getReliabilityScore());

        if (sourceRepository.existsBySourceKindAndUrlTemplate(source.getSourceKind(), source.getUrlTemplate())) {
            throw new SourceException(SourceErrorCode.DUPLICATED_SOURCE);
        }

        return SourceConverter.toCreated(saveSource(source));
    }

    @Override
    public SourceResDTO.Updated updateSource(Long sourceId, SourceReqDTO.Update request) {
        Source source = getSource(sourceId);

        String name = request.getName() == null ? source.getName() : request.getName().trim();
        boolean urlTemplateSupplied = request.getUrlTemplate() != null;
        String urlTemplate = urlTemplateSupplied
                ? SourceConverter.normalizeUrlTemplate(request.getUrlTemplate())
                : source.getUrlTemplate();
        String country = request.getCountry() == null
                ? source.getCountry()
                : request.getCountry().trim().toUpperCase(Locale.ROOT);
        String language = request.getLanguage() == null ? source.getLanguage() : request.getLanguage().trim();
        CrawlPolicy crawlPolicy = request.getCrawlPolicy() == null ? source.getCrawlPolicy() : request.getCrawlPolicy();
        BigDecimal reliabilityScore = request.getReliabilityScore() == null
                ? source.getReliabilityScore()
                : request.getReliabilityScore();
        boolean active = request.getActive() == null ? source.isActive() : request.getActive();

        validateName(name);
        if (urlTemplateSupplied) {
            validateUrlTemplate(source.getSourceKind(), urlTemplate);
        }
        validateCountryAndLanguage(country, language);
        validateReliabilityScore(reliabilityScore);

        if (!source.getUrlTemplate().equals(urlTemplate)
                && sourceRepository.existsBySourceKindAndUrlTemplateAndIdNot(
                        source.getSourceKind(), urlTemplate, sourceId)) {
            throw new SourceException(SourceErrorCode.DUPLICATED_SOURCE);
        }

        source.update(name, urlTemplate, country, language, crawlPolicy, reliabilityScore, active);
        flushSource();

        return SourceConverter.toUpdated(source);
    }

    /**
     * 이미 수집된 기사가 소스를 참조하므로 레코드를 지우지 않고 비활성화한다(soft delete).
     * 주제(topics)는 hard delete인데 소스는 다르다는 점에 주의.
     */
    @Override
    public SourceResDTO.Deleted deleteSource(Long sourceId) {
        Source source = getSource(sourceId);

        List<Topic> linkedTopics = source.getTopics();
        if (!linkedTopics.isEmpty()) {
            throw new SourceException(SourceErrorCode.SOURCE_LINKED_TO_TOPIC,
                    Map.of("linkedTopicIds", linkedTopics.stream().map(Topic::getId).toList()));
        }

        source.changeActive(false);

        return SourceConverter.toDeleted(source);
    }

    private Source getSource(Long sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new SourceException(SourceErrorCode.SOURCE_NOT_FOUND));
    }

    /**
     * 중복은 위에서 미리 막지만, 동시 요청은 유니크 제약에서만 걸린다.
     * 그 경우에도 500이 아니라 SOURCE409로 응답하도록 서비스 안에서 flush하고 변환한다.
     */
    private Source saveSource(Source source) {
        try {
            return sourceRepository.saveAndFlush(source);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicatedSource(exception);
        }
    }

    private void flushSource() {
        try {
            sourceRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicatedSource(exception);
        }
    }

    private RuntimeException translateDuplicatedSource(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();

        if (message != null && message.toUpperCase(Locale.ROOT).contains(SOURCE_UNIQUE_CONSTRAINT)) {
            return new SourceException(SourceErrorCode.DUPLICATED_SOURCE);
        }
        return exception;
    }

    private void validateKind(String sourceKind) {
        if (!Source.KIND_FEED.equals(sourceKind) && !Source.KIND_SEARCH.equals(sourceKind)) {
            throw new SourceException(SourceErrorCode.INVALID_SOURCE_KIND);
        }
    }

    private void validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "name은 필수입니다.");
        }
        if (name.length() > Source.MAX_NAME_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "name은 " + Source.MAX_NAME_LENGTH + "자 이하여야 합니다.");
        }
    }

    /**
     * FEED는 고정 URL이어야 한다. <b>SEARCH는 provider 키(NAVER/TAVILY/SERPAPI)만 받는다.</b>
     *
     * <p>등록 당시에는 {@code {query}} 자리표시자를 가진 URL 템플릿도 함께 받았는데(#9), 그걸 수집할 어댑터가
     * 없다. 등록은 201로 성공하고 실행에서만 매번 실패하니, 사용자는 소스가 잘못됐다는 사실을 수집 이력을 열어야
     * 알게 된다. <b>동작하지 않는 것을 등록 가능하게 두는 쪽이 더 나쁘다</b>(#31 B1).
     *
     * <p>범용 URL 어댑터를 만들지 않는 이유는 provider마다 인증 방식과 응답 형식이 제각각이기 때문이다 —
     * Naver는 인증 헤더, Tavily는 POST 바디, SerpAPI는 URL 안의 api_key다. URL 하나로 표현되지 않는다는
     * 판단은 {@code plan-final.md} §2-6에 이미 있다.
     */
    private void validateUrlTemplate(String sourceKind, String urlTemplate) {
        if (!StringUtils.hasText(urlTemplate)) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST, "urlTemplate은 필수입니다.");
        }
        if (urlTemplate.length() > Source.MAX_URL_TEMPLATE_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "urlTemplate은 " + Source.MAX_URL_TEMPLATE_LENGTH + "자 이하여야 합니다.");
        }

        if (Source.KIND_SEARCH.equals(sourceKind)) {
            if (SearchProvider.fromKey(urlTemplate) == null) {
                throw new SourceException(SourceErrorCode.INVALID_SEARCH_URL_TEMPLATE);
            }
            return;
        }

        if (!isHttpUrl(urlTemplate)) {
            throw new SourceException(SourceErrorCode.INVALID_FEED_URL_TEMPLATE);
        }
    }

    private void validateCountryAndLanguage(String country, String language) {
        if (country != null && country.length() > Source.MAX_COUNTRY_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "country는 ISO 국가 코드 " + Source.MAX_COUNTRY_LENGTH + "자리여야 합니다.");
        }
        if (language != null && language.length() > Source.MAX_LANGUAGE_LENGTH) {
            throw new GeneralException(GeneralErrorCode.BAD_REQUEST,
                    "language는 " + Source.MAX_LANGUAGE_LENGTH + "자 이하여야 합니다.");
        }
    }

    private void validateReliabilityScore(BigDecimal reliabilityScore) {
        if (reliabilityScore == null) {
            return;
        }
        boolean outOfRange = reliabilityScore.compareTo(Source.MIN_RELIABILITY_SCORE) < 0
                || reliabilityScore.compareTo(Source.MAX_RELIABILITY_SCORE) > 0;

        if (outOfRange) {
            throw new SourceException(SourceErrorCode.INVALID_RELIABILITY_SCORE);
        }
    }

    /**
     * 스킴만 보면 "https://"처럼 호스트가 없는 값이 통과해서 수집 시점에야 실패한다. 호스트까지 확인한다.
     *
     * <p>자리표시자를 치환하지 않고 그대로 파싱한다. {@code {}}는 URI 문법에서 허용되지 않는 문자라
     * 자리표시자가 든 값은 여기서 걸린다 — FEED는 고정 URL이어야 하므로 그게 맞는 판정이다.
     */
    private boolean isHttpUrl(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith(HTTP_PREFIX) && !lowered.startsWith(HTTPS_PREFIX)) {
            return false;
        }

        try {
            return StringUtils.hasText(URI.create(value).getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 명세대로 조회에 실패하면 상태를 unknown으로 <b>저장한 뒤</b> SOURCE502를 낸다. 실패를 응답으로만
     * 알리고 상태를 그대로 두면 목록 화면이 옛 값을 계속 보여준다.
     *
     * <p>{@code noRollbackFor}가 없으면 아래에서 던지는 예외가 방금 적은 unknown까지 함께 되돌린다.
     * 런타임 예외는 기본이 롤백이라, "저장한 뒤 502" 계약이 조용히 깨진다.
     */
    @Transactional(noRollbackFor = SourceException.class)
    @Override
    public SourceResDTO.RobotsChecked checkRobots(Long sourceId) {
        Source source = getSource(sourceId);
        RobotsDecision decision = robotsPolicyService.evaluate(source);
        decision.applyTo(source);

        if (!decision.resolved()) {
            throw new SourceException(SourceErrorCode.ROBOTS_CHECK_FAILED, Map.of(
                    "sourceId", sourceId,
                    "robotsStatus", Source.ROBOTS_STATUS_UNKNOWN,
                    "reason", decision.failureReason()));
        }

        return SourceConverter.toRobotsChecked(sourceId, decision);
    }
}
