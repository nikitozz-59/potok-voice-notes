import { BaseApp } from '@zeppos/zml/base-app'
import { log as Logger } from '@zos/utils'

const log = Logger.getLogger('voice-notes-app')

// BaseApp разворачивает messaging-канал с Side Service (BLE MessageBuilder).
App(BaseApp({
  globalData: {},
  onCreate() {
    log.log('voice-notes app onCreate')
  },
  onDestroy() {
    log.log('voice-notes app onDestroy')
  },
}))
