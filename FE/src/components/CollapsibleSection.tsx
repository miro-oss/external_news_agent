import { useId, type ReactNode } from 'react'

interface Props {
  /** 상단 액션에서 `scrollIntoView`로 찾아오므로 DOM id를 밖에서 정한다. */
  id?: string
  title: string
  description?: string
  /** 제목 옆 배지. 안을 펼치지 않고도 몇 건인지 알 수 있게 한다. */
  count?: number
  open: boolean
  onToggle: () => void
  children: ReactNode
}

/**
 * 제목 줄을 눌러 접었다 펴는 카드.
 *
 * <p>열림 상태를 안에서 들고 있지 않다. 화면 쪽에서 "소스 등록을 펼치고 그리로 스크롤" 같은 동작을
 * 해야 하는데, 상태가 이 안에 있으면 밖에서 열 방법이 없다.
 *
 * <p>접힌 동안 본문을 아예 렌더링하지 않는다. 안에 있는 폼이 소스 목록 같은 질의를 걸고 있어서,
 * `display: none`으로만 감추면 보이지도 않는 폼 때문에 요청이 나간다.
 */
export function CollapsibleSection({ id, title, description, count, open, onToggle, children }: Props) {
  const generatedId = useId()
  const panelId = `${id ?? generatedId}-panel`

  return (
    <section className={open ? 'collapsible open' : 'collapsible'} id={id}>
      <h2>
        <button
          type="button"
          className="collapsible-trigger"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={onToggle}
        >
          <span className="collapsible-heading">
            <strong>
              {title}
              {count !== undefined && <span className="collapsible-count">{count}</span>}
            </strong>
            {description && <span>{description}</span>}
          </span>
          <svg
            className="collapsible-chevron"
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m6 9 6 6 6-6" />
          </svg>
        </button>
      </h2>
      {open && <div className="collapsible-body" id={panelId}>{children}</div>}
    </section>
  )
}
