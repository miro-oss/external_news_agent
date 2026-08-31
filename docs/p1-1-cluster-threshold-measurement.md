# P1-1 이슈 클러스터링 임계값 측정

측정일: 2026-08-31

골든셋: `BE/src/test/resources/golden/clusters.v1.json` (`clusters.v1`, 200건)

Java 출력: `BE/build/reports/clusters/pairs.json`

## 방법

- 50개 수동 이슈 라벨을 출처별 제목 변형 4건으로 확장해 200건을 구성했다.
- 40개 이슈(160건)는 calibration, 10개 이슈(40건)는 holdout으로 이슈 단위 분리했다.
- Java `IssueClusterer`가 정규화 토큰, 균등 자카드, 결정론적 엔티티 교집합, 시간 차와 최종 cluster assignment를 출력했다.
- Python은 Java feature를 재구현하지 않고 자카드 `0.40~0.75`(0.05 간격) × 시간창 `24/48/72h`를 스윕했다.
- precision `0.90` 이상 후보 중 recall을 우선하고, 동률이면 더 보수적인 자카드 임계와 계획 기준인 48시간에 가까운 값을 선택했다.

## 결과

| 구분 | 자카드 | 시간창 | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|
| Calibration 선택 | 0.55 | 48h | 1.000 | 1.000 | 1.000 | 1.000 |
| Holdout 재검증 | 0.55 | 48h | 1.000 | 1.000 | 1.000 | 1.000 |
| 기존 초기값 | 0.60 | 48h | 1.000 | 0.900 | 0.946 | 0.983 |
| char_wb TF-IDF 비교군 | 0.75 | — | 1.000 | 1.000 | 1.000 | 1.000 |

## 결정

- `news.clustering.title-jaccard-threshold=0.55`
- `news.clustering.entity-time-window=48h`
- 홀드아웃 precision이 완료 기준 `0.90`을 통과하고 recall도 `0.85` 이상이므로 Oracle VECTOR 임베딩은 도입하지 않는다.
- TF-IDF 비교군과 규칙 기반 결과가 동률이어서 런타임 서비스 홉과 별도 IDF 정본을 추가할 이득이 없다.
- SimHash는 별도 양방향 테스트로 검증하며 `FULLTEXT` 기사에만 적용하고 해밍 거리 `3`을 유지한다.
