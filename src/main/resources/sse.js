/**
 * iterget - EventSource业务接口封装
 *
 * 前端
 *   https://github.com/wangzihaogithub/sse-js.git
 *
 * 后端
 *   <!-- https://mvnrepository.com/artifact/com.github.wangzihaogithub/sse-server -->
 *   <dependency>
 *      <groupId>com.github.wangzihaogithub</groupId>
 *      <artifactId>sse-server</artifactId>
 *      <version>1.0.4</version>
 *   </dependency>
 */
class Sse {
  static version = '1.0.0'
  static DEFAULT_OPTIONS = {
    url: '/common/sse',
    eventListeners: {}
  }
  static DEFAULT_RECONNECT_TIME = 5000
  static STATE_ESTABLISHED = 'ESTABLISHED'
  static STATE_CONNECT = 'CONNECT'
  static STATE_CLOSED = 'CLOSED'

  state = Sse.STATE_CLOSED
  connectionName = ''

  constructor(options) {
    this.options = Object.assign({}, Sse.DEFAULT_OPTIONS, options)

    let clientId = this.options.clientId || localStorage.getItem('sseClientId')
    if (!clientId) {
      const h = () => Math.floor(65536 * (1 + Math.random())).toString(16).substring(1)
      clientId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`
    }
    localStorage.setItem('sseClientId', clientId)
    this.clientId = clientId

    this.handleConnectionFinish = (event) => {
      const res = JSON.parse(event.data)
      this.connectionId = res.connectionId
      this.reconnectDuration = this.options.reconnectTime || res.reconnectTime || Sse.DEFAULT_RECONNECT_TIME
      this.state = Sse.STATE_ESTABLISHED
      this.connectionTimestamp = res.serverTime
      this.connectionName = res.name
    }

    this.toString = () => {
      return `${this.connectionName}:${this.state}`
    }

    this.handleOpen = () => {
      this.clearReconnectTimer()
    }

    this.handleError = () => {
      this.state = Sse.STATE_CLOSED
      this.clearReconnectTimer()
      this.timer = setTimeout(() => {
        this.es = this.newEventSource()
      }, this.reconnectDuration || this.options.reconnectTime || Sse.DEFAULT_RECONNECT_TIME)
    }

    this.newEventSource = () => {
      if (this.state !== Sse.STATE_CLOSED) {
        return this.es
      }
      this.state = Sse.STATE_CONNECT
      const es = new EventSource(`${this.options.url}/connect?clientId=${this.clientId}&clientVersion=${Sse.version}`)
      es.addEventListener('connect-finish', this.handleConnectionFinish)
      es.addEventListener('open', this.handleOpen)    // 连接成功
      es.addEventListener('error', this.handleError)  // 失败
      // 用户事件
      for (let eventName in this.options.eventListeners) {
        try {
          es.addEventListener(eventName, this.options.eventListeners[eventName])
        } catch (e) {
          console.error(`addEventListener(${eventName}) error`, e)
        }
      }
      return es
    }

    this.clearReconnectTimer = () => {
      if (typeof this.timer === 'number') {
        clearTimeout(this.timer)
        this.timer = null
      }
    }

    this.destroy = () => {
      this.clearReconnectTimer()
      this.close('destroy')
    }

    this.close = (reason = 'close') => {
      this.state = Sse.STATE_CLOSED
      if (this.es) {
        this.es.close()
      }
      const connectionId = this.connectionId
      if (connectionId !== undefined) {
        this.connectionId = undefined
        let params = new URLSearchParams()
        params.set('clientId', this.clientId)
        params.set('connectionId', connectionId)
        params.set('reason', reason)
        params.set('sseVersion', Sse.version)
        navigator.sendBeacon(`${this.options.url}/disconnect`, params)
        this.clearReconnectTimer()
        this.es = null
      }
    }

    // 页签关闭时
    window.addEventListener('unload', () => {
      this.close('unload')
    }, false)

    window.addEventListener('beforeunload', () => {
      this.close('beforeunload')
    }, false)

    // 监听浏览器窗口切换时
    document.addEventListener('visibilitychange', (e) => {
      if (document.visibilityState === 'visible') {
        this.es = this.newEventSource()
      } else {
        this.close('visibilitychange')
      }
    })

    this.es = this.newEventSource()
  }
}

export default Sse