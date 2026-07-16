const assert = require('node:assert/strict')
const test = require('node:test')
const { readFrontendFile } = require('./helpers/project-source.cjs')

test('question helper keeps only user messages and builds stable keys', async () => {
  const helperSource = readFrontendFile('src', 'utils', 'chatMessageNavigation.js')
  const helperUrl = `data:text/javascript;base64,${Buffer.from(helperSource).toString('base64')}`
  const { buildChatMessageKey, buildQuestionItems } = await import(helperUrl)
  const messages = [
    { id: 7, role: 'user', content: '讲解 Kafka', createdAt: '2026-07-05T10:00:00Z' },
    { id: 8, role: 'assistant', content: 'Kafka 是事件流平台' },
    { role: 'user', content: '消息存在哪里', createdAt: '2026-07-05T10:01:00Z' }
  ]

  assert.equal(buildChatMessageKey(messages[0], 0), 'message-7')
  assert.deepEqual(buildQuestionItems(messages), [
    { key: 'message-7', content: '讲解 Kafka', createdAt: '2026-07-05T10:00:00Z' },
    { key: 'message-user-2026-07-05T10:01:00Z-2', content: '消息存在哪里', createdAt: '2026-07-05T10:01:00Z' }
  ])
})

test('navigator is accessible and supports dismiss interactions', () => {
  const source = readFrontendFile('src', 'components', 'chat', 'ChatQuestionNavigator.vue')
  assert.match(source, /aria-label="查看历史提问"/)
  assert.match(source, /:aria-expanded="String\(open\)"/)
  assert.match(source, /aria-controls="chat-question-list"/)
  assert.match(source, /event\.key === 'Escape'/)
  assert.match(source, /event\.stopPropagation\(\)/)
  assert.match(source, /document\.addEventListener\('pointerdown'/)
  assert.match(source, /questions\.length/)
})

test('message list exposes smooth location and temporary highlight', () => {
  const source = readFrontendFile('src', 'components', 'chat', 'ChatMessageList.vue')
  assert.match(source, /:data-message-key="buildChatMessageKey\(item, idx\)"/)
  assert.match(source, /scrollIntoView/)
  assert.match(source, /question-locate-highlight/)
  assert.match(source, /prefers-reduced-motion/)
  assert.match(source, /defineExpose\(\{ locateMessage \}\)/)
})

test('drawer integrates current-session questions with the message list', () => {
  const source = readFrontendFile('src', 'components', 'chat', 'ChatAssistantDrawer.vue')
  assert.match(source, /ChatQuestionNavigator/)
  assert.match(source, /buildQuestionItems\(chatStore\.messages\)/)
  assert.match(source, /messageListRef\.value\?\.locateMessage\(key\)/)
  assert.match(source, /:questions="questionItems"/)
})
