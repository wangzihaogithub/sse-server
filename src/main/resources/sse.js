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
 *      <version>1.0.5</version>
 *   </dependency>
 */
class Sse {
  static version = '1.0.5'
  static DEFAULT_OPTIONS = {
    url: '/api/sse',
    keepaliveTime: 300000,
    eventListeners: {},
    query: {}
  }
  static DEFAULT_RECONNECT_TIME = 5000
  /**
   * CONNECTING（数值 0）
   * 连接尚未建立，或者它已关闭并且用户代理正在重新连接
   * 创建对象时，readyState必须将其设置为CONNECTING(0)。下面给出的用于处理连接的规则定义了值何时更改。
   */
  static STATE_CONNECTING = 0
  /**
   * OPEN（数值 1）
   * 用户代理有一个打开的连接，并在接收到事件时分派它们。
   */
  static STATE_OPEN = 1
  /**
   * CLOSED（数值 2）
   * 连接未打开，并且用户代理未尝试重新连接。要么存在致命错误，要么close()调用了该方法。
   */
  static STATE_CLOSED = 2

  static install = function (Vue, opts = {}) {
    window.Sse = Sse
    console.log('install Sse')
  }

  state = Sse.STATE_CLOSED
  connectionName = ''

  constructor(options) {
    this.options = Object.assign({}, Sse.DEFAULT_OPTIONS, options)
    if(!this.options.accessTimestamp){
      this.options.accessTimestamp = Date.now()
    }

    let clientId = this.options.clientId || localStorage.getItem('sseClientId')
    if (!clientId) {
      const h = () => Math.floor(65536 * (1 + Math.random())).toString(16).substring(1)
      clientId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`
    }
    localStorage.setItem('sseClientId', clientId)
    this.clientId = clientId

    this.handleConnectionFinish = (event) => {
      this.clearReconnectTimer()
      const res = JSON.parse(event.data)
      this.connectionId = res.connectionId
      this.reconnectDuration = this.options.reconnectTime || res.reconnectTime || Sse.DEFAULT_RECONNECT_TIME
      this.connectionTimestamp = res.serverTime
      this.connectionName = res.name
    }

    this.toString = () => {
      return `${this.connectionName}:${this.state}`
    }

    this.handleOpen = () => {
      this.state = Sse.STATE_OPEN
    }

    /**
     * 则将该属性CLOSED设置readyState为CLOSED并触发error在该EventSource对象上 命名的事件。一旦用户代理连接失败，它就不会尝试重新连接。
     */
    this.handleError = () => {
      this.state = Sse.STATE_CLOSED
      this.timer = setTimeout(this.newEventSource, this.reconnectDuration || this.options.reconnectTime || Sse.DEFAULT_RECONNECT_TIME)
    }

    this.newEventSource = () => {
      if (this.es) {
        if (this.es.readyState === Sse.STATE_CLOSED) {
          this.removeEventSource()
        } else {
          this.state = this.es.readyState
          return
        }
      }
      this.state = Sse.STATE_CONNECTING

      const query = new URLSearchParams()
      query.append('keepaliveTime', this.options.keepaliveTime)
      query.append('clientId', this.clientId)
      query.append('clientVersion', Sse.version)
      query.append('screen', `${window.screen.width}x${window.screen.height}`)
      query.append('accessTime', this.options.accessTimestamp)
      query.append('listeners', Object.keys(this.options.eventListeners).join(','))
      if (window.performance.memory) {
        for (let key in window.performance.memory) {
          query.append(key, window.performance.memory[key])
        }
      }
      for (let key in this.options.query) {
        query.append(key, this.options.query[key])
      }

      const es = new EventSource(`${this.options.url}/connect?${query.toString()}`)
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
      this.es = es
    }

    this.clearReconnectTimer = () => {
      if (typeof this.timer === 'number') {
        clearTimeout(this.timer)
        this.timer = null
      }
    }

    this.destroy = () => {
      this.close('destroy')
    }

    this.removeEventSource = () => {
      if (!this.es) {
        return
      }
      this.es.removeEventListener('error', this.handleError)
      this.es.removeEventListener('open', this.handleOpen)
      this.es.removeEventListener('connect-finish', this.handleConnectionFinish)
      // 用户事件
      for (let eventName in this.options.eventListeners) {
        try {
          this.es.removeEventListener(eventName, this.options.eventListeners[eventName])
        } catch (e) {
          console.error(`removeEventListener(${eventName}) error`, e)
        }
      }
      this.es.close()
      this.es = null
    }

    this.close = (reason = 'close') => {
      this.state = Sse.STATE_CLOSED
      this.removeEventSource()
      this.clearReconnectTimer()
      const connectionId = this.connectionId
      if (connectionId !== undefined) {
        this.connectionId = undefined
        let params = new URLSearchParams()
        params.set('clientId', this.clientId)
        params.set('connectionId', connectionId)
        params.set('reason', reason)
        params.set('sseVersion', Sse.version)
        navigator.sendBeacon(`${this.options.url}/disconnect`, params)
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
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.newEventSource()
      } else {
        this.close('visibilitychange')
      }
    })

    this.newEventSource()
  }
}

window.Sse = Sse
export default Sse