const fs = require('fs')
const path = require('path')
const assert = require('assert')

const apiSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'api', 'chatAssistant.js'),
  'utf8'
)
const composerSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'chat', 'ChatComposer.vue'),
  'utf8'
)

assert.match(apiSource, /transcribeChatVoice/)
assert.match(apiSource, /\/api\/chat-assistant\/voice\/transcribe/)
assert.match(apiSource, /FormData/)

assert.match(composerSource, /MediaRecorder/)
assert.match(composerSource, /chooseSupportedMimeType/)
assert.match(composerSource, /MediaRecorder\.isTypeSupported/)
assert.match(composerSource, /selectedVoiceMimeType/)
assert.match(composerSource, /MAX_RECORDING_SECONDS/)
assert.match(composerSource, /recordingSeconds/)
assert.match(composerSource, /recordingTimer/)
assert.match(composerSource, /startRecordingTimer/)
assert.match(composerSource, /clearRecordingTimer/)
assert.match(composerSource, /recording/)
assert.match(composerSource, /voiceTranscribing/)
assert.match(composerSource, /transcribeChatVoice/)
assert.match(composerSource, /draft\.value = text/)
assert.match(composerSource, /aria-label="语音输入"/)
assert.match(composerSource, /voiceHint\.value = '未识别到语音，请靠近麦克风后重试'/)
assert.match(composerSource, /onBeforeUnmount\(\(\) => \{[\s\S]*voiceTranscribing\.value = false/)
assert.match(composerSource, /onBeforeUnmount\(\(\) => \{[\s\S]*audioChunks = \[\]/)

console.log('chat voice input contract assertions passed')
