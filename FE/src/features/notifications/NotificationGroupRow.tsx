import { type FormEvent, useEffect, useRef, useState } from 'react'
import {
  useDeleteNotificationGroup,
  useNotificationGroupRecipients,
  useReplaceNotificationGroupMembers,
  useUpdateNotificationGroup,
} from '../../api/queries'
import type { NotificationGroup, NotificationRecipient } from '../../api/types'
import { Skeleton, SkeletonRegion } from '../../components/Skeleton'
import { MutationStatus } from '../settings/MutationStatus'
import { GroupRecipientPicker } from './GroupRecipientPicker'

export function NotificationGroupRow({ group, recipients, perspectiveLabel }: {
  group: NotificationGroup
  recipients: NotificationRecipient[]
  perspectiveLabel: string
}) {
  const remove = useDeleteNotificationGroup()
  const [editing, setEditing] = useState(false)
  const editButton = useRef<HTMLButtonElement>(null)

  return <article>
    <div><strong>{group.name}</strong><span>{perspectiveLabel} · {group.memberCount}명</span></div>
    <div className="recipient-actions">
      <button ref={editButton} type="button" className="text-button" aria-label={`${group.name} 편집`}
        aria-expanded={editing} aria-controls={editing ? `group-settings-${group.id}` : undefined}
        disabled={editing || remove.isPending} onClick={() => { remove.reset(); setEditing(true) }}>편집</button>
      <button type="button" className="text-button danger" aria-label={`${group.name} 삭제`}
        disabled={editing || remove.isPending} onClick={() => remove.mutate(group.id)}>삭제</button>
    </div>
    {editing && <NotificationGroupEditor group={group} recipients={recipients} onClose={() => {
      setEditing(false)
      // 닫힌 폼에서 편집 진입점으로 키보드 포커스를 돌려준다.
      requestAnimationFrame(() => editButton.current?.focus())
    }} />}
    <div className="group-row-status"><MutationStatus error={remove.error} success={null} /></div>
  </article>
}

function NotificationGroupEditor({ group, recipients, onClose }: {
  group: NotificationGroup
  recipients: NotificationRecipient[]
  onClose: () => void
}) {
  const members = useNotificationGroupRecipients(group.id)
  const update = useUpdateNotificationGroup()
  const replace = useReplaceNotificationGroupMembers()
  const [name, setName] = useState(group.name)
  const [selected, setSelected] = useState<number[] | null>(null)
  const [savedMemberIds, setSavedMemberIds] = useState<number[] | null>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => { nameInput.current?.focus() }, [])
  const memberIds = savedMemberIds ?? members.data?.content.map(recipient => recipient.id) ?? []
  const draftIds = selected ?? memberIds
  // 비활성 구성원도 유지할 수 있으므로 활성 수신자 목록과 현재 구성원을 함께 표시한다.
  const choices = [...new Map([
    ...(members.data?.content ?? []), ...recipients,
  ].map(recipient => [recipient.id, recipient])).values()]
  const changed = draftIds.length !== memberIds.length || draftIds.some(id => !memberIds.includes(id))
  const pending = update.isPending || replace.isPending

  function saveName(event: FormEvent) {
    event.preventDefault()
    if (pending || !name.trim() || name.trim() === group.name) return
    update.mutate({ groupId: group.id, name: name.trim() }, { onSuccess: result => setName(result.name) })
  }

  function saveMembers(event: FormEvent) {
    event.preventDefault()
    if (pending || !members.data || members.isError || !changed) return
    replace.mutate({ groupId: group.id, recipientIds: draftIds }, { onSuccess: result => {
      setSavedMemberIds(result.members.map(member => member.recipientId))
      setSelected(null)
    } })
  }

  return <div className="recipient-settings group-editor" id={`group-settings-${group.id}`}
    role="region" aria-label={`${group.name} 그룹 편집`} aria-busy={pending}>
    <p className="group-editor-help">그룹명과 구성원은 각각 저장됩니다.</p>
    <form className="recipient-email-form" aria-label="그룹명 편집" onSubmit={saveName}>
      <label htmlFor={`group-name-${group.id}`}>그룹명</label>
      <div className="recipient-email-fields">
        <input ref={nameInput} id={`group-name-${group.id}`} required maxLength={100} value={name} disabled={pending}
          onChange={event => { setName(event.target.value); update.reset() }} />
        <button type="submit" disabled={pending || !name.trim() || name.trim() === group.name}>
          {update.isPending ? '저장 중…' : '그룹명 저장'}
        </button>
      </div>
      <MutationStatus error={update.error} success={update.isSuccess ? '그룹명을 저장했습니다.' : null} />
    </form>
    <form className="group-members-form" aria-label="그룹 구성원 편집" onSubmit={saveMembers}>
      {members.isPending ? <SkeletonRegion label="그룹 구성원을 불러오는 중입니다.">
        <Skeleton className="skeleton-control" />
      </SkeletonRegion> : members.isError ? <div>
        <MutationStatus error={members.error} success={null} />
        <button type="button" className="secondary-button" disabled={members.isFetching || pending}
          onClick={() => void members.refetch()}>구성원 다시 불러오기</button>
      </div> : <>
        <GroupRecipientPicker recipients={choices} selected={draftIds} disabled={pending}
          onChange={ids => { setSelected(ids); replace.reset() }} />
        <p className="group-editor-help">선택을 해제하면 그룹에서 제외됩니다. 수신자 정보는 유지됩니다.</p>
        <button type="submit" className="primary-button" disabled={pending || !changed}>
          {replace.isPending ? '저장 중…' : '구성원 저장'}
        </button>
      </>}
      <MutationStatus error={replace.error} success={replace.isSuccess ? '구성원을 저장했습니다.' : null} />
    </form>
    <button type="button" className="text-button group-editor-close" disabled={pending} onClick={onClose}>편집 닫기</button>
  </div>
}
