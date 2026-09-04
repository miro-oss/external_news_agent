# 자동 수집 점검 및 독립 클러스터 기준셋 — #162

2026-09-04, 기반 코드 `b13b481`. 작업: [#162](https://github.com/miro-oss/external_news_agent/issues/162).

**수집량 조건은 충족한다. 새 표본 160건을 예측과 분리해 라벨링했지만, AI 기준 라벨에서
현재 설정과 calibration 선택값 모두 정확도 관문을 통과하지 못했다.** 사람 검수를 마친
정답셋은 아니므로 최종 일반화 성능이나 전체 안정화 완료를 선언하지 않는다.
다음 구현 판단은 서로 다른 업체의 전시회 발표가 합쳐지는 과병합을 우선 다룬다.

## 1. 수집 자료

관측 창은 **2026-09-02 13:10 ~ 09-04 13:10 KST**로 고정했다. DB의 `started_at`은
UTC DB 시계와 달리 `Asia/Seoul`의 local TIMESTAMP다. 최근 48시간 실행은 SCHEDULED
SUCCESS 24, PARTIAL 68, FAILED 62, MANUAL FAILED 1회였다. 실패·진행 중 실행은
적격 기사 추출에 쓰지 않았다. 기사 수는 스캔 수가 아니라 주제별 `distinct article_id`다.

| 주제 | 관측 고유 기사 | #145 머지 이후 처음 관측된 기사 | 선택 표본 |
|---|---:|---:|---:|
| 4386 / MEASURE3-반도체-20260901 | 218 | 214 | 80 |
| 4387 / MEASURE3-제조장비-20260901 | 223 | 222 | 80 |

| run | 주제 | 시작 시각 KST | 상태 | 관측 고유 기사 |
|---|---|---|---|---:|
| 4014 | 4386 | 09-03 11:25:08 | PARTIAL | 111 |
| 4015 | 4387 | 09-03 11:25:08 | PARTIAL | 114 |
| 4454 | 4386 | 09-04 11:26:06 | PARTIAL | 111 |
| 4455 | 4387 | 09-04 11:26:06 | PARTIAL | 110 |

최소 3 **run**·2개 주제·주제당 20건 조건에 대해 4 run·2개 주제·각 80건이다.
첫 실행부터 마지막 실행까지 24시간 58초이며, 각 주제는 일일 주기 2회다.
3개의 서로 다른 스케줄 주기를 확보했다는 뜻은 아니다. PARTIAL 허용은 실제 기사 확보를
평가하는 이번 관문에 한하며, 정상 실행 10회의 비용 측정에 그대로 포함하지 않는다.

네 run은 모두 #145 머지(09-03 11:04:35) 뒤다. 4014/4015는 #147 머지(09-03 12:41:14)
전 P2-9 가동 이력이다. run마다 실행 commit을 기록하지 않아 당시 실행 바이너리의 revision까지
입증하지는 못한다. 이번 결과는 현재 main 규칙의 오프라인 재생이다.
**사용 목적은 로컬 전용이며 원격 배포나 PR 머지 이후 실행이라는 추가 조건은 필요 없다.**
수정한 프로그램을 로컬에서 실행한 뒤 새 기사를 확인하는 것으로 후속 검증을 진행한다.

## 2. 예측과 분리한 기준 라벨

- 원문 시점은 **09-04 13:12:40 KST**의 일관된 읽기 트랜잭션이다. 해당 관측 창에 등장한
  기사의 현재 저장 제목·요약·본문이며, 과거 run 당시 상태를 복원한 replay는 아니다.
- SQL은 issue membership, 기존 content group, 예측/점수/분석 결과를 읽지 않는다.
  첫 관측이 #145 머지 전인 기사도 제외했다. 원문은 120자 조각으로 자르지 않고 보관했다.
  SQL*Plus의 한글 길이 제한과 줄 끝 공백 제거를 피하도록 900자 chunk 및 `|END` 표식을 사용한다.
- 주제별 `SHA-256(independent-162-v1:topicId:sourceArticleId)`가 작은 순서로 80건을 택했다.
  실제 원문은 159건이며 같은 원문 1건이 두 주제에 관측됐다. 가상 ID는 원본 article ID의
  정렬 순서를 보존해 Java 대표 선택의 최종 동률 처리를 바꾸지 않는다.
- 별도 AI 작업자가 원문만 읽고, 부모 AI가 모호한 사례를 예측 열람 전에 검토했다.
  **108개 사건**, HIGH 125 / MEDIUM 30 / LOW 5건이다. 사람 정답셋 검수는 남아 있다.
- 같은 구체적 발표·행동·발생 사건을 묶는다. 같은 기업·산업·전시회라는 이유만으로 묶지 않는다.
  같은 결선과 결선 결과는 한 사건이며, 전시회에서 서로 다른 업체가 낸 발표는 다른 사건이다.
- 사건 전체를 단위로 기사 수 균형을 맞춰 CALIBRATION/HOLDOUT 각각 주제별 40건을 배치했다.
  가장 큰 사건부터 주제별 양쪽 기사 수 차이 제곱합을 최소화하는 쪽에 배치하고, 동률은 고정
  SHA-256 seed `independent-162-split-v1`로 결정했다. 사건 및 동일 원문은 분할을 넘지 않는다.
- 주제별 20건 최소 조건보다 강화한 **각 분할·주제별 40건**을 두어 DF를 독립 계산했다.
  네 조합 모두 실제 투표 대표 40건으로 DF가 활성화됐다. HOLDOUT 양성 쌍 28 / 음성 쌍 1,532,
  CALIBRATION 양성 쌍 261 / 음성 쌍 1,299다. 큰 투자 발표 사건 때문에 양성 쌍 수는 불균형하다.

사전에 기존 grid(제목 0.40~0.75, 일반 시간창 24/48/72h, DF 0.05/0.10/0.15/0.20,
조직명 제목 0.10/0.125/0.15/0.20, 조직 시간창 12/24/48h), calibration 선택 및 holdout
precision ≥0.90 / recall ≥0.85 관문을 고정했다. 도구·sweep 코드와 데이터·라벨 hash를 봉인한 뒤
Java 특성을 추출하고 판정표를 한 번 실행했다. 예측 후 라벨이나 임계값을 변경하지 않았다.

## 3. 결과와 판정

| 평가 | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|
| 현재 설정 calibration | 0.9000 | 0.0690 | 0.1177 | 0.8901 |
| **현재 설정 holdout** | **0.4167** | **0.3571** | 0.3795 | 0.9641 |
| 선택 설정 calibration | 0.9706 | 0.1264 | 0.2087 | 0.9057 |
| **선택 설정 holdout** | **0.3846** | **0.5357** | 0.4420 | 0.9663 |
| char_wb TF-IDF holdout | 0.4615 | 0.2143 | 0.2887 | 0.9619 |

현재 설정은 0.50/48h/DF 0.10 + 조직 0.125/24h, calibration 선택값은
0.40/48h/DF 0.10 + 조직 0.20/12h다. TF-IDF 단독과 SimHash 전처리 비교값은 동일하다.
현재 설정 holdout은 TP 10 / FP 14 / FN 18, 선택 설정은 TP 15 / FP 24 / FN 13이다.
`decisionGatePassed=false`, 평가 프로세스 exit 1은 측정 기준 미달이며 테스트 실행 오류가 아니다.

현재 설정에서 확인한 직접 과병합 간선은 다음과 같다. 모두 같은 주제의 서로 다른 업체 발표다.

| 원본 기사 ID | 서로 다른 발표 | 제목 Jaccard | 엔티티/조직 겹침 |
|---|---|---:|---:|
| 3797 ↔ 3771 | 한화세미텍 ↔ 디엠에스, 세미콘 타이완 장비 공개 | 0.5000 | 0 / 0 |
| 3797 ↔ 3738 | 한화세미텍 ↔ 한미반도체, 패키징 장비 공개 | 0.5000 | 0 / 0 |
| 3771 ↔ 3739 | 디엠에스 ↔ 한화세미텍, 전시회 참가·라인업 공개 | 0.6364 | 0 / 0 |

**전시회명·공통 발표 문구만으로 제목 임계값을 넘어 서로 다른 업체가 연결되고, 전이적 병합으로
오류 쌍이 늘어난다.** LOW 라벨 5건이 이 직접 간선의 판정을 만든 것은 아니다.
본문 품질 문제도 별도로 있다. 원문 표본의 `FULLTEXT` 125건은 유효 본문 125건을 뜻하지 않는다.
추천 기사 목록만 담긴 속보, 사진 설명·푸터뿐인 기사 등이 있어 제목/요약만으로 라벨링한 사례를
불확실성으로 남겼다. FETCH_FAILED 32, ROBOTS_DISALLOWED 3건도 표본에서 제거하지 않았다.

사전 판정표의 `precision < 0.90`에 해당하므로 **임베딩 검토를 진행하기 전에 과병합을 수정하는
것이 다음 순서**다. 이번 노출된 holdout을 튜닝 후 다시 독립 검증이라고 부르지 않는다.
사용자에게는 소수 기사 쌍으로 원하는 사건 경계를 확인받는다. 이것을 전체 160건의 사람 정답셋
검증으로 확대 해석하지 않는다. 정식 독립 성능 수치가 필요할 때는 수정본의 로컬 실행에서
새 표본을 확보해 예측과 분리한 라벨링 및 새로운 holdout 평가를 진행한다.
정상 run 10회 비용·A11 1주 비용·나머지 보류 기능 조건은 별도 후속 작업이다.

## 4. 도구 및 재현

공유 가능한 수치·hash·오류 간선은 [summary JSON](clusters.independent.162.summary.json)에 있다.
실제 기사 원문·라벨 CSV·Java 특성·전체 sweep report는 로컬 산출물로 보관한다.
golden SHA-256: `1fdc3d58e05d30b136012ce2b1b64eb5983b23c04baa9db20510a76a79ec7bfb`.
세부 hash는 summary의 `independentValidation`과 pack의 `seal.json`을 참조한다.

1. `independent-readiness.sql`에 KST 기준 시각과 24 또는 48시간을 전달해 수집 통계를 확인한다.
2. `independent-articles.sql`에 같은 시각·창·최초 관측 하한·두 주제 ID를 전달한다.
   SQL*Plus를 `NLS_LANG=AMERICAN_AMERICA.AL32UTF8`로 실행하고 출력은 Git 밖에 둔다.
3. `python -m app.eval.cluster_snapshot --chunks <raw.txt> --output <snapshot.json>
   --window-end <offset ISO time> --first-seen-since <offset ISO time>`로 표본을 고정한다.
   이 도구의 query token 변환은 이번 단순 한국어 키워드에 맞춘 공백 분할이다.
4. `python -m app.eval.cluster_independent prepare --snapshot <snapshot.json> --output-dir <new-pack>`.
   원문만 보고 `labels.csv`의 사건·분할·근거를 채운다. 예측을 본 라벨러는 참여하지 않는다.
5. `python -m app.eval.cluster_independent freeze --pack <new-pack>`.
6. BE에서 `CLUSTERS_INDEPENDENT_GOLDEN=<new-pack>/golden.json
   CLUSTERS_INDEPENDENT_OUTPUT=<new-pack>/java-pairs.json ./gradlew test
   --tests '*IssueClustererIndependentExportTest.exportsFrozenIndependentGoldenFeatures' --rerun-tasks`.
7. `python -m app.eval.cluster_independent evaluate --pack <new-pack>
   --java-pairs <new-pack>/java-pairs.json`. 이미 평가된 pack은 재평가를 거절한다.

검증 도구 테스트: Python 46개, Java 합성 5개 및 실제 frozen 입력 export 1개 통과.
원문 chunk의 공백·유니코드·누락 검증, 라벨/입력 변조, split 누수, 양성 쌍 부재, Java DF 분리를
검사했다. 기존 Python sweep에서 다른 주제의 전역 대표 proxy 간선이 빠지던 평가 오류도 합성
테스트로 재현해 고쳤다. 제품 API 작업은 없으며 운영 클러스터 규칙은 이번 측정에서 조정하지 않았다.
