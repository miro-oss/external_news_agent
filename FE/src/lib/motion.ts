/**
 * 움직임 최소화 설정을 존중하는 스크롤 헬퍼.
 *
 * <p>CSS에는 `prefers-reduced-motion` 규칙이 있지만, `scrollIntoView({ behavior: 'smooth' })`처럼
 * 자바스크립트가 직접 지정한 움직임은 그 규칙이 닿지 않는다. 스크롤은 화면 전체가 흐르는 큰
 * 움직임이라, 전정기관이 예민한 사람에게는 카드 페이드보다 이쪽이 훨씬 부담이 크다.
 *
 * <p>설정을 켠 사람에게는 같은 자리로 즉시 이동한다. 도착점은 같고 가는 방식만 다르다.
 */
export function prefersReducedMotion(): boolean {
  // 미디어 쿼리를 지원하지 않는 환경에서는 판단할 근거가 없다. 기본 동작을 막지 않는다.
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 요소를 화면 가운데로 보낸다. 움직임 최소화 설정이 켜져 있으면 애니메이션 없이 건너뛴다. */
export function scrollIntoViewGently(element: Element) {
  element.scrollIntoView({
    behavior: prefersReducedMotion() ? 'auto' : 'smooth',
    block: 'center',
  })
}
