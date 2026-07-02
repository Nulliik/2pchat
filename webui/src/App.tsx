import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'

type ChatEvent = {
  type: string
  [key: string]: unknown
}

type Settings = {
  nickname: string
  mode: string
  host: string
  bind: string
  port: number
  transport: string
}

const defaultSettings: Settings = {
  nickname: 'You',
  mode: 'connect',
  host: '127.0.0.1',
  bind: '0.0.0.0',
  port: 4444,
  transport: 'direct',
}

export function App() {
  const [online, setOnline] = useState(false)
  const [settings, setSettings] = useState<Settings>(defaultSettings)
  const [log, setLog] = useState<string[]>([])
  const [message, setMessage] = useState('')
  const wsRef = useRef<WebSocket | null>(null)

  const wsUrl = useMemo(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const host = window.location.hostname || '127.0.0.1'
    return `${protocol}://${host}:8000/ws/chat`
  }, [])

  useEffect(() => {
    const ws = new WebSocket(wsUrl)
    wsRef.current = ws

    ws.onmessage = (evt) => {
      const payload = JSON.parse(evt.data) as ChatEvent
      if (payload.type === 'state') {
        setOnline(Boolean(payload.online))
        setSettings((current) => ({ ...current, ...(payload.settings as Settings) }))
        return
      }
      if (payload.type === 'settings') {
        setSettings((current) => ({ ...current, ...(payload.settings as Settings) }))
        return
      }
      if (payload.type === 'status') {
        setOnline(payload.state === 'online')
      }
      if (payload.type === 'message') {
        const author = String(payload.author || 'unknown')
        const body = String(payload.body || '')
        setLog((current) => [...current, `${author}: ${body}`])
      }
    }

    return () => ws.close()
  }, [wsUrl])

  const send = (event: ChatEvent) => {
    const payload = JSON.stringify(event)
    wsRef.current?.send(payload)
  }

  const onSettingsSubmit = (e: FormEvent) => {
    e.preventDefault()
    send({ type: 'settings_update', settings })
  }

  const onSend = (e: FormEvent) => {
    e.preventDefault()
    if (!message.trim()) return
    send({ type: 'chat_message', body: message })
    setMessage('')
  }

  return (
    <div className="app">
      <header className="header">
        <h1>2P Chat</h1>
        <span className={online ? 'chip online' : 'chip offline'}>
          {online ? 'Online' : 'Offline'}
        </span>
        <span className="destination">{settings.host}:{settings.port}</span>
      </header>

      <section className="settings">
        <form onSubmit={onSettingsSubmit}>
          <input
            value={settings.nickname}
            onChange={(e) => setSettings({ ...settings, nickname: e.target.value })}
            placeholder="Nickname"
          />
          <select
            value={settings.mode}
            onChange={(e) => setSettings({ ...settings, mode: e.target.value })}
          >
            <option value="connect">connect</option>
            <option value="listen">listen</option>
            <option value="rendezvous">rendezvous</option>
          </select>
          <select
            value={settings.transport}
            onChange={(e) => setSettings({ ...settings, transport: e.target.value })}
          >
            <option value="direct">direct</option>
            <option value="ygg">ygg</option>
            <option value="ygg-embedded">ygg-embedded</option>
          </select>
          <input
            value={settings.host}
            onChange={(e) => setSettings({ ...settings, host: e.target.value })}
            placeholder="Destination host"
          />
          <input
            value={settings.bind}
            onChange={(e) => setSettings({ ...settings, bind: e.target.value })}
            placeholder="Bind address"
          />
          <input
            type="number"
            min={1}
            max={65535}
            value={settings.port}
            onChange={(e) =>
              setSettings({
                ...settings,
                port: Number.parseInt(e.target.value || '0', 10) || 0,
              })
            }
            placeholder="Port"
          />
          <button type="submit">Save settings</button>
          <button type="button" onClick={() => send({ type: 'connect' })}>
            Connect
          </button>
          <button type="button" onClick={() => send({ type: 'disconnect' })}>
            Disconnect
          </button>
        </form>
      </section>

      <main className="chatlog">
        {log.map((line, idx) => (
          <p key={`${line}-${idx}`}>{line}</p>
        ))}
      </main>

      <form className="composer" onSubmit={onSend}>
        <input
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Type a message"
        />
        <button type="submit">Send</button>
      </form>
    </div>
  )
}
