import { AI_TECHNOLOGY_PRESET, type TopicPreset } from './topicPresets'
import './topic-presets.css'

export function TopicPresetPicker({ disabled = false, onSelect }: {
  disabled?: boolean
  onSelect: (preset: TopicPreset) => void
}) {
  return <fieldset className="topic-preset-picker" disabled={disabled}>
    <legend>AI 기술 동향 함께 수집</legend>
    <p className="hint">모델·Agent·학습·검색·온톨로지를 한 주제로 함께 수집합니다. 아래 조건을 채운 뒤 수정하고 저장할 수 있습니다.</p>
    <button type="button" className="secondary-button topic-preset-action" disabled={disabled}
      onClick={() => onSelect(AI_TECHNOLOGY_PRESET)}>AI 기술 동향 조건 채우기</button>
  </fieldset>
}
