import { ApiError } from '../../api/client'

interface Props {
  error: unknown
  successMessage: string | null
}

/**
 * 실패 문구는 서버가 준 것을 그대로 쓴다.
 *
 * 명세가 코드마다 사용자 문구를 정해 두었다(TOPIC409 "이미 존재하는 주제명입니다.",
 * SOURCE400 "SEARCH 소스의 URL 템플릿은 provider 키…"). 화면이 따로 지어내면 같은 실패가
 * 서버 로그와 화면에서 다르게 불려서, 사용자가 말한 문구로 로그를 찾을 수 없게 된다.
 * 코드도 같이 보여 주는 이유가 그것이다.
 */
export function FormStatus({ error, successMessage }: Props) {
  if (error) {
    const text = error instanceof ApiError ? `${error.message} (${error.code})` : '등록에 실패했습니다.'
    return <p className="error" role="alert">{text}</p>
  }
  if (successMessage) {
    return <p className="success" role="status">{successMessage}</p>
  }
  return null
}
