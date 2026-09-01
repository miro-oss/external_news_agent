# P2-1 키워드 IDF 정규화 측정

측정일: 2026-09-01

작업 이슈: #126

범위: 수집 후보 선별, 본문 확보, 분석 대상 우선순위

## 계산 계약

선택(OR) 키워드만 아래 비율로 점수화한다. 필수(AND)·제외(NOT) 키워드는 기존처럼 통과 여부만
결정한다.

```text
topicFit = Σ idf(k) for matched k / Σ idf(k) for all optional k
idf(k)   = log((N + 1) / (df(k) + 1)) + 1
```

- `N`, `df`: 최근 30일에 저장한 같은 언어의 기사 제목·요약 corpus
- 캐시 키: `(language_code, normalized_keyword)`
- 갱신 경계: 캐시가 없거나 `refreshed_at`부터 24시간이 지난 첫 점수 계산
- 빈 corpus: smoothing 결과 `idf=1.0`
- 선택 키워드 없음: `topicFit=1.0`
- 캐시 장애: 마지막 정상 값을 사용하고, 값이 없으면 균등 가중치로 폴백한 뒤 5분 후 재시도

## 동일 run 후보 순위 변화

`CollectionCandidatePrioritizerTest.prioritizesOneRareMatchOverTwoCommonMatches`의 한 배치에 두 후보를
넣어 비교했다. 이해하기 쉽도록 흔한 키워드의 IDF는 1, 희귀 키워드는 4로 고정한 결정론적 표본이다.

| 후보 | 일치 키워드 | 균등 `metadataFit` | IDF `topicFit` | 균등 순위 | IDF 순위 |
|---|---|---:|---:|---:|---:|
| 흔한 표현 2개 | 반도체, AI | 0.6667 | 0.3333 | 1 | 2 |
| 희귀 표현 1개 | HBM4 | 0.3333 | 0.6667 | 2 | 1 |

희귀 키워드 한 개를 정확히 포함한 후보가 흔한 키워드 두 개를 포함한 후보보다 먼저 본문 확보 대상으로
선정됐다. 저장 여부와 AND/NOT 게이트는 바뀌지 않는다.

## 주제 크기 정규화

`TopicFitScorerTest.normalizesTwentyKeywordAndThreeKeywordTopicsToTheSameScale`에서 20개 키워드 주제와
3개 키워드 주제를 같은 0~1 척도로 비교했다.

| 주제 | 전체 IDF 합 | 일치 IDF 합 | `topicFit` |
|---|---:|---:|---:|
| 선택 키워드 20개 | 20 | 10 | 0.5 |
| 선택 키워드 3개 | 4 | 2 | 0.5 |

키워드 개수가 아니라 전체 IDF 대비 일치 IDF의 비율이 같으면 같은 점수가 나온다.

## 갱신·Oracle 검증

- 단위 테스트에서 최초 계산 후 23시간 59분까지 재집계하지 않고, 정확히 24시간째 다시 집계함을 확인했다.
- `ko`와 `en` corpus의 `N`, `df`, IDF가 서로 섞이지 않음을 확인했다.
- 로컬 Oracle 26ai Free에서 V30 마이그레이션, CLOB 요약의 대소문자 무시 `df` 집계, MERGE 업서트와
  재조회까지 통과했다.
- 전체 백엔드 테스트: 657개 실행, 실패 0, 오류 0(통합 테스트 102개는 기본 실행에서 조건부 제외).

## 제외한 범위

클러스터링 가중 자카드는 포함하지 않았다. 균등 자카드가 기존 홀드아웃에서 precision/recall 1.000이라
P2-1에서 개선 여지가 없다는 2026-08-31 범위 축소 결정을 따른다.
