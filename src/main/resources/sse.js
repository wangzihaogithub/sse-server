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
 *      <version>1.2.1</version>
 *   </dependency>
 */
class Sse {
  static version = '1.2.1'
  static DEFAULT_OPTIONS = {
    url: '/api/sse',
    keepaliveTime: 900000,
    eventListeners: {},
    intercepts: [],
    query: {},
    withCredentials: true,
    clientId: null,
    accessTimestamp: null,
    reconnectTime: null,
    useWindowEventBus: true
  }
  static IMPORT_MODULE_TIMESTAMP = Date.now()
  static DEFAULT_RECONNECT_TIME = 5000
  /**
   * CONNECTING（数值 0）
   * 连接尚未建立，或者它已关闭并且用户代理正在重新连接
   * 创建对象时，readyState必须将其设置为CONNECTING(0)。下面给出的用于处理连接的规则定义了值何时更改。
   */
  static STATE_CONNECTING = EventSource.CONNECTING
  /**
   * OPEN（数值 1）
   * 用户代理有一个打开的连接，并在接收到事件时分派它们。
   */
  static STATE_OPEN = EventSource.OPEN
  /**
   * CLOSED（数值 2）
   * 连接未打开，并且用户代理未尝试重新连接。要么存在致命错误，要么close()调用了该方法。
   */
  static STATE_CLOSED = EventSource.CLOSED

  static install = function(global = window, opts = {}) {
    global.Sse = Sse
    console.log('install Sse')
  }

  state = Sse.STATE_CONNECTING
  createTimestamp = Date.now()
  connectionName = ''
  retryQueue = []

  constructor(options) {
    this.options = Object.assign({}, Sse.DEFAULT_OPTIONS, options)

    if (this.options.eventListeners instanceof Array) {
      const eventListeners = {}
      this.options.eventListeners.forEach(name => {
        eventListeners[name] = null
      })
      this.options.eventListeners = eventListeners
    }

    if (!this.options.accessTimestamp) {
      const accessTimestamp = sessionStorage.getItem('sseAccessTimestamp')
      this.options.accessTimestamp = accessTimestamp ? Number(accessTimestamp) : Date.now()
    }
    sessionStorage.setItem('sseAccessTimestamp', `${this.options.accessTimestamp}`)

    let clientId = this.options.clientId || localStorage.getItem('sseClientId')
    const h = () => Math.floor(65536 * (1 + Math.random())).toString(16).substring(1)
    if (!clientId) {
      clientId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`
    }
    localStorage.setItem('sseClientId', clientId)
    this.clientId = clientId
    this.instanceId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`

    this.handleConnectionFinish = (event) => {
      this.clearReconnectTimer()
      const res = JSON.parse(event.data)
      this.connectResponse = res
      this.connectionId = res.connectionId
      this.reconnectDuration = this.options.reconnectTime || res.reconnectTime || Sse.DEFAULT_RECONNECT_TIME
      this.connectionTimestamp = res.serverTime
      this.connectionName = res.name
      this.serverVersion = res.version

      this.flush()
    }

    this.flush = () => {
      let task
      while ((task = this.retryQueue.shift())) {
        switch (task.type) {
          case 'addListener': {
            this.addListener(task.eventListeners).then(task.resolve).catch(task.reject)
            break
          }
          case 'removeListener': {
            this.removeListener(task.eventListeners).then(task.resolve).catch(task.reject)
            break
          }
          case 'send': {
            this.send(task.path, task.body, task.query, task.headers).then(task.resolve).catch(task.reject)
            break
          }
          case 'upload': {
            this.upload(task.path, task.formData, task.query, task.headers).then(task.resolve).catch(task.reject)
            break
          }
          default: {
            break
          }
        }
      }
    }

    this.handleConnectionClose = (event) => {
      if (this.isActive()) {
        this.flush()
      }
      this.state = Sse.STATE_CLOSED
      const res = JSON.parse(event.data)
      this.closeResponse = res
      this.removeEventSource()
      this.clearReconnectTimer()
    }

    this.toString = () => {
      return `${this.connectionName}:${this.state}:${this.createTimestamp}`
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
      query.append('keepaliveTime', String(this.options.keepaliveTime))
      query.append('clientId', this.clientId)
      query.append('clientVersion', Sse.version)
      query.append('screen', `${window.screen.width}x${window.screen.height}`)
      query.append('accessTime', this.options.accessTimestamp)
      query.append('listeners', Object.keys(this.options.eventListeners).join(','))
      query.append('useWindowEventBus', String(this.options.useWindowEventBus))
      query.append('locationHref', location.href)
      query.append('clientImportModuleTime', String(Sse.IMPORT_MODULE_TIMESTAMP))
      query.append('clientInstanceTime', String(this.createTimestamp))
      query.append('clientInstanceId', this.instanceId)

      if (window.performance.memory) {
        for (const key in window.performance.memory) {
          query.append(key, window.performance.memory[key])
        }
      }
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }

      const es = new EventSource(`${this.options.url}/connect?${query.toString()}`, { withCredentials: this.options.withCredentials })
      es.addEventListener('connect-finish', this.handleConnectionFinish)
      es.addEventListener('connect-close', this.handleConnectionClose)
      es.addEventListener('open', this.handleOpen) // 连接成功
      es.addEventListener('error', this.handleError) // 失败

      // 用户事件
      for (const eventName in this.options.eventListeners) {
        const fn = this.options.eventListeners[eventName]
        this._addEventListener(es, eventName, fn)
      }
      this.es = es
    }

    this.getEventListeners = () => {
      return Object.assign({}, this.options.eventListeners)
    }

    this._addEventListener = (es, eventName, fn) => {
      if (!es) {
        return false
      }
      try {
        if (fn) {
          es.addEventListener(eventName, fn)
        }
        if (this.options.useWindowEventBus) {
          es.addEventListener(eventName, this._dispatchEvent)
        }
        return true
      } catch (e) {
        console.error(`addEventListener(${eventName}) error`, e)
        return false
      }
    }

    this._removeEventListener = (es, eventName, fn) => {
      if (!es) {
        return false
      }
      try {
        try {
          es.removeEventListener(eventName, fn)
        } catch (e) {
          console.warn(`removeEventListener(${eventName}) error`, e)
        }
        if (this.options.useWindowEventBus) {
          es.removeEventListener(eventName, this._dispatchEvent)
        }
        return true
      } catch (e) {
        console.error(`addEventListener(${eventName}) error`, e)
        return false
      }
    }

    this._dispatchEvent = (event) => {
      event.url = this.options.url
      for (const intercept of this.options.intercepts) {
        try {
          intercept.apply(this, [event])
        } catch (e) {
          console.warn('intercept error ', e)
        }
      }
      const newEvent = new MessageEvent(event.type, event)
      newEvent.url = this.options.url
      window.dispatchEvent(newEvent)
    }

    this.addListener = (eventListeners, fn) => {
      let eventListenersMap = {}
      if (typeof eventListeners === 'string' || typeof eventListeners === 'symbol') {
        eventListenersMap[eventListeners] = fn
      } else if (eventListeners instanceof Array) {
        eventListeners.forEach(name => {
          eventListenersMap[name] = null
        })
      } else {
        eventListenersMap = eventListeners
      }
      if (!this.isActive()) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ type: 'addListener', eventListeners: eventListenersMap, resolve, reject })
        })
      }

      const body = {
        connectionId: this.connectionId,
        listener: Object.keys(eventListenersMap)
      }

      const query = new URLSearchParams()
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }
      try {
        const responsePromise = fetch(`${this.options.url}/addListener?${query.toString()}`, {
          method: 'POST',
          body: JSON.stringify(body),
          credentials: 'include',
          mode: 'cors',
          headers: {
            'content-type': 'application/json;charset=UTF-8'
          }
        })
        for (const eventName in eventListenersMap) {
          const fn = eventListenersMap[eventName]
          this.options.eventListeners[eventName] = fn
          this._addEventListener(this.es, eventName, fn)
        }
        return responsePromise
      } catch (e) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ type: 'addListener', eventListeners: eventListenersMap, resolve, reject })
        })
      }
    }

    this.removeListener = (eventListeners, fn) => {
      let eventListenersMap = {}
      if (typeof eventListeners === 'string' || typeof eventListeners === 'symbol') {
        eventListenersMap[eventListeners] = fn
      } else if (eventListeners instanceof Array) {
        eventListeners.forEach(name => {
          eventListenersMap[name] = null
        })
      } else {
        eventListenersMap = eventListeners
      }
      if (!this.isActive()) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ type: 'removeListener', eventListeners: eventListenersMap, resolve, reject })
        })
      }

      const body = {
        connectionId: this.connectionId,
        listener: Object.keys(eventListenersMap)
      }
      const query = new URLSearchParams()
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }
      try {
        const responsePromise = fetch(`${this.options.url}/removeListener?${query.toString()}`, {
          method: 'POST',
          body: JSON.stringify(body),
          credentials: 'include',
          mode: 'cors',
          headers: {
            'content-type': 'application/json;charset=UTF-8'
          }
        })
        for (const eventName in eventListenersMap) {
          const fn = eventListenersMap[eventName]
          this.options.eventListeners[eventName] = fn
          this._removeEventListener(this.es, eventName, fn)
        }
        return responsePromise
      } catch (e) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ type: 'removeListener', eventListeners: eventListenersMap, resolve, reject })
        })
      }
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

    this.switchURL = (newUrl) => {
      if (this.options.url === newUrl) {
        return
      }
      this.options.url = newUrl
      this.close('switchURL')
      this.newEventSource()
    }

    this.connect = this.newEventSource

    this.removeEventSource = () => {
      if (!this.es) {
        return
      }
      this.es.removeEventListener('error', this.handleError)
      this.es.removeEventListener('open', this.handleOpen)
      this.es.removeEventListener('connect-finish', this.handleConnectionFinish)
      this.es.removeEventListener('connect-close', this.handleConnectionClose)
      // 用户事件
      for (const eventName in this.options.eventListeners) {
        try {
          const fn = this.options.eventListeners[eventName]
          if (fn) {
            this.es.removeEventListener(eventName, fn)
          }
          if (this.options.useWindowEventBus) {
            window.removeEventListener(eventName, this._dispatchEvent)
          }
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
        const params = new URLSearchParams()
        params.set('clientId', this.clientId)
        params.set('connectionId', connectionId)
        params.set('reason', reason)
        params.set('sseVersion', Sse.version)
        navigator.sendBeacon(`${this.options.url}/disconnect`, params)
      }
    }

    this.isActive = () => {
      return Boolean(this.es && this.es.readyState === Sse.STATE_OPEN && this.connectionId !== undefined)
    }

    // 给后台发消息
    this.send = (path = '', body = {}, query = {}, headers = {}) => {
      if (!this.isActive()) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ path, body, query, headers, resolve, reject })
        })
      }
      const queryBuilder = new URLSearchParams()
      queryBuilder.append('connectionId', this.connectionId)
      for (const key in query) {
        queryBuilder.append(key, query[key])
      }
      try {
        return fetch(`${this.options.url}/message/${path}?${queryBuilder.toString()}`, {
          method: 'POST',
          body: JSON.stringify(body),
          credentials: 'include',
          mode: 'cors',
          headers: {
            'content-type': 'application/json;charset=UTF-8',
            ...headers
          }
        })
      } catch (e) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ path, body, query, headers, resolve, reject })
        })
      }
    }

    // 给后台传文件
    this.upload = (path = '', formData, query = {}, headers = {}) => {
      if (!(formData instanceof FormData)) {
        return Promise.reject({ message: 'sse upload() error! body must is formData! example : new FormData()' })
      }
      if (!this.isActive()) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ path, formData, query, headers, resolve, reject })
        })
      }
      const queryBuilder = new URLSearchParams()
      queryBuilder.append('connectionId', this.connectionId)
      for (const key in query) {
        queryBuilder.append(key, query[key])
      }
      try {
        return fetch(`${this.options.url}/upload/${path}?${queryBuilder.toString()}`, {
          method: 'POST',
          body: formData,
          credentials: 'include',
          mode: 'cors',
          headers: headers
        })
      } catch (e) {
        return new Promise((resolve, reject) => {
          this.retryQueue.push({ path, formData, query, headers, resolve, reject })
        })
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
    window.sse = this
  }
}

if (window.Sse === undefined) {
  window.Sse = Sse
}

export default Sse
