import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'

export interface SegmentedOption<T extends string> {
  value: T
  label: ReactNode
}

interface Props<T extends string> {
  value: T
  options: ReadonlyArray<SegmentedOption<T>>
  onSelect: (value: T) => void
  /** 트랙 전체를 설명하는 이름. 버튼 라벨만으로는 무엇을 고르는 건지 알 수 없다. */
  label?: string
  /** 화면에 이미 라벨이 있으면 그 id를 준다. 같은 말을 두 번 읽히지 않는다. */
  labelledBy?: string
  /** 폭을 채우거나 줄바꿈 규칙을 다르게 주는 곳에서 붙인다. */
  className?: string
  disabled?: boolean
  /**
   * 고른 칸을 무엇이라고 읽어 줄지. 값을 고르는 자리는 'pressed'(눌린 버튼), 지금 보고 있는 화면을
   * 가리키는 내비게이션은 'current'다. 화면 이동을 "눌린 상태"로 읽으면 뜻이 어긋난다.
   */
  activeAria?: 'pressed' | 'current'
}

interface Thumb {
  left: number
  top: number
  width: number
  height: number
}

/**
 * 배타 선택지 서넛을 나란히 놓는 세그먼트 컨트롤.
 *
 * <p>고른 칸을 흰 알약으로 칠하는 대신, 알약 하나를 트랙 위에 띄워 두고 그것이 자리를 옮긴다.
 * 칠하는 방식이면 한쪽이 꺼지고 다른 쪽이 켜지는 두 사건이라 눈이 둘을 따로 본다. 하나가 미끄러져
 * 가면 "내가 저기서 여기로 옮겼다"는 한 사건이 되고, 그 사이 거리가 두 선택지의 관계를 보여 준다.
 *
 * <p>알약의 자리는 CSS로 셀 수 없다 — 칸마다 글자 수가 달라 폭이 다르고, 좁은 화면에서는 두 줄로
 * 접히기도 한다. 그래서 고른 버튼의 실제 위치를 재서 transform으로 옮긴다. 폭이 바뀌면(창 크기,
 * 접기·펴기, 글꼴 로딩) ResizeObserver가 다시 재 준다.
 *
 * <p>첫 그림에서는 애니메이션을 끈다. 재기 전 위치는 0이라, 켜 두면 화면이 열릴 때마다 알약이
 * 왼쪽 끝에서 달려오는 것처럼 보인다.
 */
export function Segmented<T extends string>({
  value, options, onSelect, label, labelledBy, className, disabled = false, activeAria = 'pressed',
}: Props<T>) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [thumb, setThumb] = useState<Thumb | null>(null)
  const [ready, setReady] = useState(false)
  // options는 부르는 쪽에서 매번 새로 만드는 배열이라 그대로 의존하면 측정 → 렌더가 서로를 부른다.
  const optionKey = options.map((option) => option.value).join('|')

  useLayoutEffect(() => {
    const track = trackRef.current
    if (!track) return

    function measure() {
      const active = track?.querySelector<HTMLElement>('[data-active="true"]')
      if (!active) {
        setThumb(null)
        return
      }
      const next: Thumb = {
        left: active.offsetLeft,
        top: active.offsetTop,
        width: active.offsetWidth,
        height: active.offsetHeight,
      }
      setThumb((current) => (current
        && current.left === next.left && current.top === next.top
        && current.width === next.width && current.height === next.height)
        ? current
        : next)
    }

    measure()
    // 두 번째 프레임부터 움직인다. 첫 측정값은 "원래 저기 있었다"로 두어야 한다.
    const frame = requestAnimationFrame(() => setReady(true))
    // 트랙만 보면 놓치는 변화가 있다 — 폭이 그대로인 채 한 칸의 라벨만 길어지는 경우(예: 늦게
    // 도착한 "기본" 표식). 칸마다 붙여 둔다.
    const observer = new ResizeObserver(measure)
    observer.observe(track)
    track.querySelectorAll('.segmented-option').forEach((option) => observer.observe(option))
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [value, optionKey])

  return (
    <div
      ref={trackRef}
      className={className ? `segmented ${className}` : 'segmented'}
      role="group"
      aria-label={label}
      aria-labelledby={labelledBy}
    >
      {thumb && (
        <span
          className={ready ? 'segmented-thumb moving' : 'segmented-thumb'}
          aria-hidden="true"
          style={{
            transform: `translate(${thumb.left}px, ${thumb.top}px)`,
            width: thumb.width,
            height: thumb.height,
          }}
        />
      )}
      {options.map((option) => {
        const active = value === option.value
        return (
          <button
            key={option.value}
            type="button"
            data-active={active}
            aria-pressed={activeAria === 'pressed' ? active : undefined}
            aria-current={activeAria === 'current' && active ? 'page' : undefined}
            className={active ? 'segmented-option active' : 'segmented-option'}
            disabled={disabled}
            onClick={() => onSelect(option.value)}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}
