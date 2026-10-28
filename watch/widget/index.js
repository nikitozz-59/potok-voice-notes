import { create, id, codec } from '@zos/media'
import { readFileSync, writeFileSync, statSync } from '@zos/fs'
import { createWidget, widget, prop, align } from '@zos/ui'
import { getDiskInfo } from '@zos/device'
import { log as Logger } from '@zos/utils'
import { showToast } from '@zos/interaction'
import { queryPermission, requestPermission } from '@zos/app'

// Карточка-виджет «Поток» (SecondaryWidget, свайп от циферблата).
// Дизайн в едином стиле приложения: тёмный фон, статус-бар, крупная кнопка записи.

const log = Logger.getLogger('vn-widget')
const NOTES_FILE = 'data://notes.json'
const MAX_REC_S = 120
const MIN_FREE = 60 * 1024 * 1024

const C = {
  BG: 0x000000,
  SURFACE: 0x16233d,
  CARD: 0x1b2a4a,
  PRIMARY: 0xffffff,
  SECONDARY: 0x8e939a,
  ACCENT: 0x2c6fe8,
  REC: 0x00e676, // только индикатор записи
  STOP: 0xff1744,
  SYNC: 0x2c6fe8,
}

let rec = null
let recording = false
let startedAt = 0
let recId = 0
let recFile = ''
let tickTimer = null
let wBtn, wStatus, wTimer, wBadge

function loadNotes() {
  try {
    const s = readFileSync({ path: NOTES_FILE, options: { encoding: 'utf8' } })
    if (!s) return []
    const d = JSON.parse(s)
    return (Array.isArray(d) ? d : (d && d.items) || []).filter((n) => n && n.id)
  } catch (e) { return [] }
}

function saveNotes(arr) {
  try {
    writeFileSync({ path: NOTES_FILE, data: JSON.stringify({ ver: 1, items: arr }), options: { encoding: 'utf8' } })
  } catch (e) { log.log('save err ' + e) }
}

function freeSpace() {
  try { return getDiskInfo().free || 0 } catch (e) { return 0 }
}

function pad2(n) {
  return (n < 10 ? '0' : '') + n
}

function setBtn() {
  if (!wBtn) return
  if (recording) {
    wBtn.setProperty(prop.MORE, {
      text: '■',
      text_size: 48,
      normal_color: C.STOP,
      press_color: 0xd50000,
    })
  } else {
    wBtn.setProperty(prop.MORE, {
      text: '●',
      text_size: 48,
      normal_color: C.ACCENT,
      press_color: 0x1e5bc6,
    })
  }
}

function updateStatus() {
  if (recording) {
    const s = Math.round((Date.now() - startedAt) / 1000)
    if (wTimer) wTimer.setProperty(prop.MORE, { text: pad2(Math.floor(s / 60)) + ':' + pad2(s % 60) })
    if (wStatus) wStatus.setProperty(prop.MORE, { text: 'Идёт запись…', color: C.REC })
  } else {
    if (wTimer) wTimer.setProperty(prop.MORE, { text: '00:00' })
    const all = loadNotes()
    const queued = all.filter((n) => n.status === 'queued').length
    if (wBadge) wBadge.setProperty(prop.MORE, { text: queued > 0 ? String(queued) : '' })
    if (wStatus) {
      if (queued > 0) {
        wStatus.setProperty(prop.MORE, { text: queued + ' в очереди', color: C.SYNC })
      } else {
        wStatus.setProperty(prop.MORE, { text: 'Готов к записи', color: C.SECONDARY })
      }
    }
  }
}

function micPermissionGranted() {
  try {
    const q = queryPermission({ permissions: ['device:os.mic'] })
    return Array.isArray(q) && q[0] === 2
  } catch (e) {
    return false
  }
}

function ensureMicPermission(cb) {
  if (micPermissionGranted()) { if (cb) cb(true); return }
  try {
    requestPermission({
      permissions: ['device:os.mic'],
      callback: (result) => {
        const ok = Array.isArray(result) && result[0] === 2
        if (cb) cb(ok)
      },
    })
  } catch (e) {
    if (cb) cb(false)
  }
}

function startRec() {
  if (recording) return

  const free = freeSpace()
  if (free && free < MIN_FREE) {
    showToast({ content: 'мало места' })
    return
  }

  try {
    if (!rec) rec = create(id.RECORDER)
    if (!rec) {
      // Разрешение могло быть не выдано — запросим и повторим
      ensureMicPermission((ok) => {
        if (!ok) {
          showToast({ content: 'нет доступа к микрофону' })
          return
        }
        try {
          rec = create(id.RECORDER)
          if (rec) startRec()
          else showToast({ content: 'микрофон занят' })
        } catch (e2) {
          showToast({ content: 'ошибка микрофона' })
        }
      })
      return
    }
    recId = Date.now()
    recFile = 'data://note_' + recId + '.opus'
    rec.setFormat(codec.OPUS, { target_file: recFile })
    rec.start()
    recording = true
    startedAt = Date.now()
    setBtn()
    updateStatus()
    if (tickTimer) clearInterval(tickTimer)
    tickTimer = setInterval(tick, 1000)
    showToast({ content: 'запись…' })
  } catch (e) {
    recording = false
    if (rec) { try { rec.stop() } catch (e2) {} rec = null }
    log.log('start err ' + e)
    showToast({ content: 'ошибка записи' })
    setBtn()
  }
}

function tick() {
  updateStatus()
  const s = Math.round((Date.now() - startedAt) / 1000)
  if (s >= MAX_REC_S) {
    stopRec()
    showToast({ content: 'автостоп ' + MAX_REC_S + 'с' })
    return
  }
  if (s % 10 === 0 && freeSpace() && freeSpace() < MIN_FREE) {
    stopRec()
    showToast({ content: 'автостоп: мало места' })
  }
}

function stopRec() {
  if (!recording) return
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
  recording = false
  try { if (rec) rec.stop() } catch (e) {}
  setBtn()

  const nid = recId
  const file = recFile
  const dur = Math.max(1, Math.round((Date.now() - startedAt) / 1000))

  setTimeout(() => {
    let size = 0
    try { size = statSync({ path: file }).size } catch (e) {}

    if (!size) {
      showToast({ content: 'пустая запись' })
      updateStatus()
      return
    }

    const notes = loadNotes()
    notes.unshift({
      id: nid, at: startedAt, dur: dur, size: size,
      status: 'queued', file: file, text: null, err: null,
      type: 'note', summary: '', dueIso: null,
    })
    saveNotes(notes)
    showToast({ content: 'сохранено ' + Math.round(size / 1024) + 'КБ' })
    updateStatus()
    log.log('widget saved note ' + nid + ' ' + size + 'B')
  }, 400)
}

SecondaryWidget({
  build() {
    // Шапка: бренд «Поток» и бейдж очереди
    createWidget(widget.TEXT, {
      x: 20, y: 36, w: 200, h: 32,
      text: 'Поток', text_size: 24,
      color: C.ACCENT, align_h: align.LEFT,
    })

    wBadge = createWidget(widget.TEXT, {
      x: 310, y: 36, w: 60, h: 32,
      text: '', text_size: 22,
      color: C.SYNC, align_h: align.RIGHT,
    })

    // Таймер
    wTimer = createWidget(widget.TEXT, {
      x: 10, y: 92, w: 370, h: 64,
      text: '00:00', text_size: 56,
      color: C.PRIMARY, align_h: align.CENTER_H,
    })

    // Статус
    wStatus = createWidget(widget.TEXT, {
      x: 10, y: 168, w: 370, h: 28,
      text: 'Готов к записи', text_size: 20,
      color: C.SECONDARY, align_h: align.CENTER_H,
    })

    // Круглая кнопка записи в стиле главного экрана
    wBtn = createWidget(widget.BUTTON, {
      x: 130, y: 216, w: 130, h: 130, radius: 65,
      normal_color: C.ACCENT, press_color: 0x1e5bc6,
      text: '●', text_size: 48,
      click_func: () => { if (recording) stopRec(); else startRec() },
    })

    // Подсказка снизу
    createWidget(widget.TEXT, {
      x: 20, y: 366, w: 350, h: 24,
      text: 'быстрая голосовая заметка', text_size: 16,
      color: 0x546e7a, align_h: align.CENTER_H,
    })

    // Рекордер создаём лениво в startRec() после подтверждения разрешения.
    ensureMicPermission(null)
    updateStatus()
  },

  onDestroy() {
    if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
    if (recording && rec) {
      try { rec.stop() } catch (e) {}
      recording = false
      try {
        const st = statSync({ path: recFile })
        if (st && st.size > 0) {
          const notes = loadNotes()
          notes.unshift({
            id: recId, at: startedAt,
            dur: Math.max(1, Math.round((Date.now() - startedAt) / 1000)),
            size: st.size, status: 'queued', file: recFile, text: null, err: null,
            type: 'note', summary: '', dueIso: null,
          })
          saveNotes(notes)
        }
      } catch (e) {}
    }
    rec = null
  },
})
