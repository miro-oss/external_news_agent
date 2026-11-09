import { AI_TOPIC_PRESETS, type TopicPreset } from './topicPresets'
import './topic-presets.css'

export function TopicPresetPicker({ disabled = false, onSelect }: {
  disabled?: boolean
  onSelect: (preset: TopicPreset) => void
}) {
  return <fieldset className="topic-preset-picker" disabled={disabled}>
    <legend>AI 기술 주제 예시</legend>
    <p className="hint">선택하면 주제명과 기사 조건을 채웁니다. 수정한 뒤 저장해 주세요.</p>
    <div className="topic-preset-options">
      {AI_TOPIC_PRESETS.map(preset => <button key={preset.id} type="button" className="secondary-button"
        disabled={disabled} onClick={() => onSelect(preset)}>
        <strong>{preset.name}</strong><span>{preset.description}</span>
      </button>)}
    </div>
  </fieldset>
}
