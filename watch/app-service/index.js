import { BasePage } from '@zeppos/zml/base-page'
import { readFileSync, writeFileSync, rmSync } from '@zos/fs'
import { log as Logger } from '@zos/utils'
import { exit } from '@zos/app-service'

// Фоновая отправка заметок (Zepp OS App Service, continuous running).
// Запускается страницей в onPause/onDestroy, если в очереди есть заметки.
// Работает после закрытия страницы — пока телефон (Zepp App) рядом и релей отвечает.
// Без setTimeout (запрещён в App Service) — последовательный await по чанкам.

const log = Logger.getLogger('vn-bg')
const NOTES_FILE = 'data://notes.json'
const CHUNK_CHARS = 2048
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function loadNotes() {
  try {
    const s = readFileSync({ path: NOTES_FILE, options: { encoding: 'utf8' } })
    if (!s) return []
    const d = JSON.parse(s)
    return (Array.isArray(d) ? d : (d && d.items) || []).filter((n) => n && n.id)
  } catch (e) {
    return []
  }
}

function saveNotes(arr) {
  try {
    writeFileSync({
      path: NOTES_FILE,
      data: JSON.stringify({ ver: 1, items: arr }),
      options: { encoding: 'utf8' },
    })
  } catch (e) {
    log.log('bg save err ' + e)
  }
}

function errText(e) {
  return String(e && e.message ? e.message : e).slice(0, 120)
}

// Uint8Array → base64. Без побайтовых конкатенаций (быстро на больших записях).
function abToBase64(bytes) {
  const len = bytes.length
  let out = ''
  let i = 0
  for (; i + 2 < len; i += 3) {
    const t = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2]
    out += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] + B64[(t >> 6) & 63] + B64[t & 63]
  }
  const left = len - i
  if (left === 1) {
    const t = bytes[i] << 16
    out += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] + '=='
  } else if (left === 2) {
    const t = (bytes[i] << 16) | (bytes[i + 1] << 8)
    out += B64[(t >> 18) & 63] + B64[(t >> 12) & 63] + B64[(t >> 6) & 63] + '='
  }
  return out
}

async function sendNote(self, n) {
  let buf
  try {
    buf = readFileSync({ path: n.file })
    if (!buf) return { kind: 'stt', err: 'файл не читается (' + Math.round((n.size || 0) / 1024) + 'КБ)' }
  } catch (e) {
    return { kind: 'stt', err: 'чтение: ' + errText(e) }
  }

  let bytes
  try {
    bytes = new Uint8Array(buf)
  } catch (e) {
    return { kind: 'stt', err: 'байты: ' + errText(e) }
  }
  buf = null

  let b64
  try {
    b64 = abToBase64(bytes)
    bytes = null
  } catch (e) {
    return { kind: 'stt', err: 'base64: ' + errText(e) }
  }
  if (!b64.length) return { kind: 'stt', err: 'файл пуст' }

  const total = Math.ceil(b64.length / CHUNK_CHARS)
  const name = 'note_' + n.id + '.opus'
  log.log('bg send ' + n.id + ': ' + b64.length + ' b64 / ' + total + ' chunks')

  for (let idx = 0; idx < total; idx++) {
    const data = b64.slice(idx * CHUNK_CHARS, (idx + 1) * CHUNK_CHARS)
    let r
    try {
      r = await self.request({
        method: 'STT_CHUNK',
        params: { id: n.id, idx: idx, total: total, name: name, language: 'ru', data: data },
      })
    } catch (e) {
      return { kind: 'transport', err: errText(e) }
    }
    if (!r) return { kind: 'transport', err: 'пустой ответ (chunk ' + idx + ')' }
    if (r.error) {
      // 'relay HTTP…' = постоянная ошибка STT; иначе транспортная → повтор.
      const kind = String(r.error).indexOf('relay HTTP') === 0 ? 'stt' : 'transport'
      return { kind: kind, err: String(r.error) }
    }
    if (typeof r.text === 'string') return { kind: 'done', text: r.text || '(тишина)' }
    // {ok:true} — промежуточный ACK, продолжаем.
  }
  return { kind: 'transport', err: 'side не собрал транскрипт' }
}

AppService(
  BasePage({
    async onInit(e) {
      log.log('bg service onInit ' + e)
      try {
        let progressed = true
        while (progressed) {
          progressed = false
          const notes = loadNotes()
          const n = notes.find((x) => x.status === 'queued' || x.status === 'sending')
          if (!n) break

          n.status = 'sending'
          n.err = null
          saveNotes(notes)

          const res = await sendNote(this, n)

          // Перечитываем — за время отправки состояние могло измениться.
          const notes2 = loadNotes()
          const cur = notes2.find((x) => x.id === n.id)
          if (cur) {
            if (res.kind === 'done') {
              cur.status = 'done'
              cur.text = res.text
              cur.err = null
              try {
                rmSync({ path: cur.file })
              } catch (e) {}
              progressed = true
            } else {
              // Транспортная ошибка — оставим в очереди, но не зацикливаемся тут:
              // выйдем из сервиса; страница (когда откроют) или следующий запуск добьют.
              cur.status = 'queued'
              cur.err = res.err
            }
            saveNotes(notes2)
          }
          if (res.kind !== 'done') break
        }
      } catch (e) {
        log.log('bg err ' + errText(e))
      }
      log.log('bg service exit')
      try {
        exit()
      } catch (e) {}
    },

    onDestroy() {
      log.log('bg service destroy')
    },
  })
)
