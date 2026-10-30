import { BaseSideService } from '@zeppos/zml/base-side'

// Голосовые заметки — side-service: JSON-прокси на локальный релей (Termux, 127.0.0.1:8080).
//
// Почему так (см. architecture.md, Блокер #2): Zepp side-fetch умеет отправлять только
// UTF-8 строку в body — бинарный multipart невозможен. Поэтому:
//   часы присылают base64 строкой → мы кладём её в JSON (строка!) → шлём на localhost:8080.
// Релей (Termux+Python) раскодирует base64, декодирует Zepp-OPUS в WAV через libopus
// и уже с настоящим бинарём делает multipart в Nexara.

const RELAY_URL = 'http://127.0.0.1:8080'

// Накопитель кусков base64 по id заметки. Часы шлют по одному куску за раз
// (STT_CHUNK) и ждут ACK — это даёт backpressure по BLE (иначе zml шлёт все
// чанки разом и ble.send падает с "send message error" на длинных записях).
// На последнем куске (idx === total-1) собираем base64 и шлём релею.
const chunks = new Map()

function parseBody(r) {
  try {
    if (r && typeof r.body === 'string') return JSON.parse(r.body)
    return r && r.body
  } catch (e) {
    return null
  }
}

function errStr(e) {
  return String(e && e.message ? e.message : e).slice(0, 80)
}

AppSideService(
  BaseSideService({
    onInit() {
      console.log('voice-notes side onInit, relay=' + RELAY_URL)
    },

    async onRequest(req, res) {
      console.log('side onRequest: ' + (req && req.method))
      if (!req) {
        res(null, { error: 'no req' })
        return
      }

      // PING — часы проверяют, живы ли Zepp App, релей и ключ Nexara.
      if (req.method === 'PING') {
        try {
          const r = await fetch({ url: RELAY_URL + '/ping', method: 'GET' })
          const b = parseBody(r)
          res(null, {
            pong: true,
            relay: {
              pong: !!(b && b.pong),
              v: b && b.service,
              key: b && b.key,
              libopus: b && b.libopus,
            },
          })
        } catch (e) {
          res(null, { pong: true, relay: { err: errStr(e) } })
        }
        return
      }

      // SYNC — часы подтягивают актуальные заметки с телефона (двусторонняя синхронизация).
      // Пагинация: params.since + params.limit прокидываются как query → /sync?since=&limit=.
      if (req.method === 'SYNC') {
        try {
          const p = req.params || {}
          let url = RELAY_URL + '/sync'
          const q = []
          if (p.since != null && p.since !== '') q.push('since=' + encodeURIComponent(p.since))
          if (p.limit != null && p.limit !== '') q.push('limit=' + encodeURIComponent(p.limit))
          if (q.length) url += '?' + q.join('&')
          const r = await fetch({ url: url, method: 'GET' })
          const b = parseBody(r)
          if (b && Array.isArray(b.notes)) {
            // Прокидываем next_since / has_more для пагинации (если телефон их прислал).
            res(null, {
              notes: b.notes,
              next_since: b.next_since,
              has_more: b.has_more,
            })
          } else {
            res(null, { error: 'sync bad response' })
          }
        } catch (e) {
          res(null, { error: errStr(e) })
        }
        return
      }

      // STT_CHUNK — кусок base64 от часов. На последнем куске — собрать и отправить релею.
      if (req.method === 'STT_CHUNK') {
        const p = req.params || {}
        const id = p.id
        const idx = p.idx
        const total = p.total
        const data = typeof p.data === 'string' ? p.data : ''

        if (id == null || !total) {
          res(null, { error: 'bad chunk params' })
          return
        }

        // Только одна заметка в полёте за раз — если пришёл новый id, чистим старое.
        if (chunks.size && !chunks.has(id)) chunks.clear()

        let entry = chunks.get(id)
        if (!entry) {
          entry = { name: p.name || 'rec.opus', language: p.language || 'ru', total: total, parts: [] }
          chunks.set(id, entry)
        }

        entry.parts[idx] = data
        const received = entry.parts.filter((x) => typeof x === 'string').length
        console.log('chunk ' + id + ' ' + (idx + 1) + '/' + total + ' (' + data.length + ' chars)')

        if (idx < total - 1) {
          res(null, { ok: true, received: received })
          return
        }

        // Последний кусок. Если пришли не все — ошибка (где-то потеряли кусок).
        if (received < total) {
          chunks.delete(id)
          res(null, { error: 'chunks lost: ' + received + '/' + total })
          return
        }

        const audio = entry.parts.join('')
        const name = entry.name
        const lang = entry.language
        const noteId = id  // явный id для тела запроса и сверки ответа
        chunks.delete(id) // освободить память до тяжёлой работы

        console.log('STT assembled: b64=' + audio.length + ' name=' + name + ' id=' + noteId)
        try {
          const r = await fetch({
            url: RELAY_URL + '/transcribe',
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ audio: audio, name: name, language: lang, id: noteId }),
          })
          const b = parseBody(r)
          console.log('relay HTTP ' + (r && r.status))

          // v3.4: телефон отвечает {accepted:true, id} мгновенно — STT/LLM идёт асинхронно,
          // транскрипт приедет через SYNC. Сверяем id: при расхождении часы зависнут (сервер
          // не знает их id), поэтому ошибка.
          if (b && b.accepted === true) {
            if (b.id != null && b.id !== noteId) {
              res(null, { error: 'id mismatch: server ' + b.id + ' ≠ watch ' + noteId })
            } else {
              res(null, { accepted: true, id: noteId, via: 'relay' })
            }
          } else if (b && typeof b.text === 'string') {
            // Старый путь (телефон не обновлён) — транскрипт сразу.
            res(null, { text: b.text, via: 'relay' })
          } else if (b && b.transient) {
            // Временный сетевой сбой — часы оставят заметку в очереди и повторят позже.
            res(null, { error: 'сеть: ' + (b.error || 'сбой') })
          } else {
            res(null, {
              error:
                'relay HTTP ' + (r && r.status) + ': ' + (b ? JSON.stringify(b).slice(0, 80) : 'null'),
            })
          }
        } catch (e) {
          console.log('STT err: ' + e)
          res(null, { error: 'relay: ' + errStr(e) })
        }
        return
      }

      // STT — старый путь одним сообщением (для совместимости; новые часы шлют STT_CHUNK).
      if (req.method === 'STT') {
        const audio = req.params && req.params.audio
        const name = (req.params && req.params.name) || 'rec.opus'
        const lang = (req.params && req.params.language) || 'ru'
        const noteId = (req.params && req.params.id) || null
        if (!audio) {
          res(null, { error: 'no audio' })
          return
        }
        console.log('STT -> relay: b64=' + audio.length + ' name=' + name + ' id=' + noteId)

        try {
          const bodyObj = { audio: audio, name: name, language: lang }
          if (noteId != null) bodyObj.id = noteId
          const r = await fetch({
            url: RELAY_URL + '/transcribe',
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bodyObj),
          })
          const b = parseBody(r)
          console.log('relay HTTP ' + (r && r.status))

          if (b && b.accepted === true) {
            res(null, { accepted: true, id: noteId, via: 'relay' })
          } else if (b && typeof b.text === 'string') {
            // Полный транскрипт без усечения — длинные заметки целиком.
            res(null, { text: b.text, via: 'relay' })
          } else if (b && b.transient) {
            res(null, { error: 'сеть: ' + (b.error || 'сбой') })
          } else {
            res(null, {
              error:
                'relay HTTP ' + (r && r.status) + ': ' + (b ? JSON.stringify(b).slice(0, 80) : 'null'),
            })
          }
        } catch (e) {
          console.log('STT err: ' + e)
          res(null, { error: 'relay: ' + errStr(e) })
        }
        return
      }

      res(null, { error: 'unknown method ' + req.method })
    },

    onRun() {},
    onDestroy() {
      console.log('voice-notes side onDestroy')
    },
  })
)
