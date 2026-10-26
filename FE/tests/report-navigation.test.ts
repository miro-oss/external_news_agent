import assert from 'node:assert/strict'
import { test } from 'node:test'
import { reportHash, reportScopeFromHash } from '../src/features/reports/reportNavigation.ts'

test('report scope survives reloads and empty selections while legacy report links keep the RUN default', () => {
  for (const scope of ['RUN', 'DAILY', 'WEEKLY'] as const) {
    for (const id of [null, 217]) {
      const hash = reportHash(scope, id)
      assert.equal(reportScopeFromHash(hash), scope)
      assert.equal(new URLSearchParams(hash.split('?')[1]).get('reportId'), id === null ? null : '217')
    }
  }
  for (const hash of ['#/reports', '#/reports?reportId=117', '#/reports?reportScope=invalid']) {
    assert.equal(reportScopeFromHash(hash), 'RUN')
  }
})
