import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  failed: boolean
}

/**
 * 한 화면이 던진 예외가 앱 전체를 내리지 않게 막는다.
 *
 * <p>리포트 탭에서 근거 카드 하나가 터졌을 때 React가 트리 전체를 언마운트했다. 내비게이션까지
 * 같이 사라져서 다른 탭으로 옮길 수도, 뒤로 가기로 돌아올 수도 없는 흰 화면이 남았다. 여기서
 * 잡아 두면 실패한 자리에만 안내가 서고 나머지는 살아 있다.
 *
 * <p>클래스로 두는 이유는 하나다 — `getDerivedStateFromError`에 대응하는 훅이 아직 없다.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 화면에는 사유를 적지 않는다. 사람이 할 수 있는 일이 없는 글이고, 개발 중에 필요한 값은
    // 콘솔에서 스택과 함께 보는 편이 낫다.
    console.error(error, info.componentStack)
  }

  render() {
    if (!this.state.failed) {
      return this.props.children
    }
    return (
      <main className="state-panel error" role="alert">
        <strong>이 화면을 여는 중 문제가 생겼습니다.</strong>
        <span>다시 시도해도 같으면 잠시 후 새로고침해 주세요.</span>
        <button
          type="button"
          className="secondary-button"
          onClick={() => this.setState({ failed: false })}
        >
          다시 시도
        </button>
      </main>
    )
  }
}
