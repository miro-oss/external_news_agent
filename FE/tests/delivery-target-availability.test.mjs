import assert from 'node:assert/strict'
import test from 'node:test'
import { unavailableDeliveryTargets } from '../src/features/notifications/deliveryTargetAvailability.ts'

const active = (id, name) => ({ id, name, active: true })
const stopped = (id, name) => ({ id, name, active: false })

test('keeps stopped and deleted saved targets visible across all target types', () => {
  const value = { channelIds: [1, 2, 99], groupIds: [3], recipientIds: [4] }
  const unavailable = unavailableDeliveryTargets(value, {
    channelIds: [active(1, '이메일'), stopped(2, '텔레그램')],
    groupIds: [stopped(3, '전략팀')], recipientIds: [],
  })
  assert.deepEqual(unavailable.map(({ key, id }) => [key, id]), [
    ['channelIds', 2], ['channelIds', 99], ['groupIds', 3], ['recipientIds', 4],
  ])
  assert.match(unavailable[0].label, /텔레그램.*사용 중지/)
  assert.match(unavailable[1].label, /더 이상 사용할 수 없음/)
  assert.deepEqual(value, { channelIds: [1, 2, 99], groupIds: [3], recipientIds: [4] })
})

test('removing an unavailable selection retains every other saved choice', () => {
  const catalog = { channelIds: [active(1, '이메일')], groupIds: [active(3, '전략팀')], recipientIds: [] }
  const before = { channelIds: [1], groupIds: [3], recipientIds: [4] }
  assert.equal(unavailableDeliveryTargets(before, catalog).length, 1)
  assert.deepEqual(unavailableDeliveryTargets({ ...before, recipientIds: [] }, catalog), [])
  assert.deepEqual(before.recipientIds, [4])
})

test('inactive entries do not block policies when they were not selected', () => {
  assert.deepEqual(unavailableDeliveryTargets({ channelIds: [1], groupIds: [], recipientIds: [] }, {
    channelIds: [active(1, '이메일'), stopped(2, '텔레그램')],
    groupIds: [stopped(3, '전략팀')], recipientIds: [stopped(4, '이름')],
  }), [])
})
