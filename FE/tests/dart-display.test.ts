import assert from 'node:assert/strict'
import test from 'node:test'
import { dartAmountUnit, dartChartAmount, dartChartSegments, formatDartAmount, formatDartMargin, safeDartFilingUrl } from '../src/features/reports/dartDisplay.ts'

test('money preserves null, zero, losses, decimal rounding, and precision above Number safe range', () => {
  assert.equal(formatDartAmount(null, 'KRW'), '자료 없음')
  assert.equal(formatDartAmount('0', 'KRW'), '0')
  assert.equal(formatDartAmount('-1', 'KRW'), '-0.00000001')
  assert.equal(formatDartAmount('1', 'KRW'), '0.00000001')
  assert.equal(formatDartAmount('-165000000', 'KRW'), '-1.7')
  assert.equal(formatDartAmount('164999999.99', 'KRW'), '1.6')
  assert.equal(formatDartAmount('9007199254740993123456789', 'KRW'), '90,071,992,547,409,931.2')
  assert.equal(formatDartAmount('9007199254740993123456789.12', 'USD'), '9,007,199,254,740,993,123,456,789.12')
  assert.equal(dartAmountUnit('KRW'), '억원')
  assert.equal(dartAmountUnit('USD'), 'USD')
  assert.equal(dartAmountUnit(null), '통화 미확인')
  assert.equal(formatDartMargin(null), '자료 없음')
  assert.equal(formatDartMargin(0), '0%')
  assert.equal(formatDartMargin(-12.45), '-12.45%')
  for (const invalid of ['', 'NaN', 'Infinity', 'not money']) assert.equal(formatDartAmount(invalid, 'KRW'), '자료 없음')
})

test('chart leaves gaps for missing quarters and distinguishes zero from negative data', () => {
  assert.equal(dartChartAmount(null, 'KRW'), null)
  assert.equal(dartChartAmount('0', 'KRW'), 0)
  assert.equal(dartChartAmount('-300000000', 'KRW'), -3)
  assert.deepEqual(dartChartSegments([null, 0, -3, null, 4, null]), [
    [{ index: 1, value: 0 }, { index: 2, value: -3 }], [{ index: 4, value: 4 }],
  ])
})

test('filing links require official HTTPS host, path and matching receipt only', () => {
  const receipt = '20260814001234'
  const url = `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${receipt}`
  assert.equal(safeDartFilingUrl(url, receipt), url)
  for (const unsafe of ['javascript:alert(1)', '//dart.fss.or.kr', url.replace('https:', 'http:'),
    url.replace('dart.fss.or.kr', 'dart.fss.or.kr.evil.com'), url.replace('/dsaf001/main.do', '/redirect'),
    `${url}&redirect=https://evil.com`, url.replace('dart.fss.or.kr', 'user:password@dart.fss.or.kr'),
    url.replace('dart.fss.or.kr', 'dart.fss.or.kr:8888'), `${url}#fragment`]) {
    assert.equal(safeDartFilingUrl(unsafe, receipt), null)
  }
  assert.equal(safeDartFilingUrl(url, '20260814009999'), null)
  assert.equal(safeDartFilingUrl(url, 'invalid'), null)
})
