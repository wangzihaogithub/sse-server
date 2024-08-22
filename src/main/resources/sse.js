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
 *      <version>1.2.17</version>
 *   </dependency>
 */
class Sse {
  static version = '1.2.17'
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
    useWindowEventBus: true,
    leaveTimeout: 5000,
    leaveCheckInterval: 500,
    sseDurationKey: '',
    windowGlobal: window,
    documentGlobal: document,
    jsonGlobal: JSON
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
  /**
   * 服务器主动触发了关闭连接
   * 谁主动触发了关闭连接
   */
  static SERVER_TRIGGER_CLOSE = 'server'
  /**
   * 客户端主动触发了关闭连接
   * 谁主动触发了关闭连接
   */
  static CLIENT_TRIGGER_CLOSE = 'client'

  state = Sse.STATE_CONNECTING
  createTimestamp = Date.now()
  connectionName = ''
  retryQueue = []
  isPageActive = true
  lastActiveTime = Date.now()
  durationTime = Date.now()

  constructor(options) {
    this.options = Object.assign({}, Sse.DEFAULT_OPTIONS, options)

    if (this.options.eventListeners instanceof Array) {
      const eventListeners = {}
      this.options.eventListeners.forEach(name => {
        eventListeners[name] = null
      })
      this.options.eventListeners = eventListeners
    }

    let {
      MessageEvent: window_MessageEvent,
      EventSource: window_EventSource,
      URLSearchParams: window_URLSearchParams,
      performance: window_performance,
      screen: window_screen,
      localStorage: window_localStorage,
      sessionStorage: window_sessionStorage,
      dispatchEvent: window_dispatchEvent,
      removeEventListener: window_removeEventListener,
      requestAnimationFrame: window_requestAnimationFrame,
      addEventListener: window_addEventListener
    } = this.options.windowGlobal

    let {
      addEventListener: document_addEventListener,
      getVisibilityState = () => this.options.documentGlobal.visibilityState
    } = this.options.documentGlobal

    let {
      parse: json_parse,
      stringify: json_stringify
    } = this.options.jsonGlobal

    if (!this.options.sseDurationKey) {
      this.options.sseDurationKey = `sseDuration${this.options.url}`
    }
    if (!this.options.accessTimestamp) {
      const accessTimestamp = window_sessionStorage.getItem('sseAccessTimestamp')
      this.options.accessTimestamp = accessTimestamp ? Number(accessTimestamp) : Date.now()
    }
    window_sessionStorage.setItem('sseAccessTimestamp', `${this.options.accessTimestamp}`)

    let clientId = this.options.clientId || window_localStorage.getItem('sseClientId')
    const h = () => Math.floor(65536 * (1 + Math.random())).toString(16).substring(1)
    if (!clientId) {
      clientId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`
    }
    window_localStorage.setItem('sseClientId', clientId)
    this.clientId = clientId
    this.instanceId = `${h() + h()}-${h()}-${h()}-${h()}-${h()}${h()}${h()}`
    this.clientClose = null

    this.handleConnectionFinish = (event) => {
      this.clearReconnectTimer()
      const res = json_parse(event.data)
      this.connectResponse = res
      this.connectionId = res.connectionId
      this.reconnectDuration = this.options.reconnectTime || res.reconnectTime || Sse.DEFAULT_RECONNECT_TIME
      this.connectionTimestamp = res.serverTime
      this.connectionName = res.name
      this.serverVersion = res.version

      this.flush()
      if (!this.isPageActive) {
        this.isPageActive = true
        this.lastActiveTime = Date.now()
        setTimeout(() => {
          this.handleChangeActiveEvent(false, true)
        }, 0)
      }
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

    this.handleSetDuration = (event) => {
      let res = json_parse(event.data)
      window_sessionStorage.setItem(this.options.sseDurationKey, res && res.duration || '0')
    }

    this.handleConnectionClose = (event) => {
      this.state = Sse.STATE_CLOSED
      setTimeout(this.removeEventSource, 0)
      this.clearReconnectTimer()
      this.closeResponse = json_parse(event.data)
    }

    this.toString = () => {
      return `${this.connectionName}:${this.state}:${this.createTimestamp}`
    }

    this.handleOpen = () => {
      this.clientClose = null
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

      const query = new window_URLSearchParams()
      query.append('sessionDuration', window_sessionStorage.getItem(this.options.sseDurationKey) || '0')
      query.append('keepaliveTime', String(this.options.keepaliveTime))
      query.append('clientId', this.clientId)
      query.append('clientVersion', Sse.version)
      query.append('screen', `${window_screen.width}x${window_screen.height}`)
      query.append('accessTime', this.options.accessTimestamp)
      query.append('listeners', Object.keys(this.options.eventListeners).join(','))
      query.append('useWindowEventBus', String(this.options.useWindowEventBus))
      query.append('locationHref', location.href)
      query.append('clientImportModuleTime', String(Sse.IMPORT_MODULE_TIMESTAMP))
      query.append('clientInstanceTime', String(this.createTimestamp))
      query.append('clientInstanceId', this.instanceId)

      if (window_performance.memory) {
        for (const key in window_performance.memory) {
          query.append(key, window_performance.memory[key])
        }
      }
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }

      const es = new window_EventSource(`${this.options.url}/connect?${query.toString()}`, { withCredentials: this.options.withCredentials })
      es.addEventListener('connect-finish', this.handleConnectionFinish)
      es.addEventListener('connect-close', this.handleConnectionClose)
      es.addEventListener('_set-duration', this.handleSetDuration) // 设置统计时长
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
      if (eventName === 'connect-close') {
        const oldFn = fn
        fn = (event) => {
          const closeInfo = {
            whoTriggerClose: this.clientClose ? Sse.CLIENT_TRIGGER_CLOSE : Sse.SERVER_TRIGGER_CLOSE,
            closeReason: this.clientClose ? this.clientClose.reason : 'server-close'
          }
          try {
            for (const key in closeInfo) {
              event[key] = closeInfo[key]
            }
          } catch (e) {
            // skip
          }
          oldFn.apply(this, [event, closeInfo])
        }
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
      const newEvent = new window_MessageEvent(event.type, event)
      newEvent.url = this.options.url
      window_dispatchEvent(newEvent)
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

      const query = new window_URLSearchParams()
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }
      try {
        const responsePromise = fetch(`${this.options.url}/connect/addListener.do?${query.toString()}`, {
          method: 'POST',
          body: json_stringify(body),
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
      const query = new window_URLSearchParams()
      for (const key in this.options.query) {
        query.append(key, this.options.query[key])
      }
      try {
        const responsePromise = fetch(`${this.options.url}/connect/removeListener.do?${query.toString()}`, {
          method: 'POST',
          body: json_stringify(body),
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
      this._close('switchURL')
      this.newEventSource()
    }

    this.connect = this.newEventSource

    this.removeEventSource = () => {
      if (!this.es) {
        return
      }
      this.connectionId = undefined
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
            window_removeEventListener(eventName, this._dispatchEvent)
          }
        } catch (e) {
          console.error(`removeEventListener(${eventName}) error`, e)
        }
      }
      this.es.close()
      this.es = null
    }

    this.close = (reason = 'close') => {
      this._close(reason)
      window_sessionStorage.removeItem(this.options.sseDurationKey)
    }

    this._close = (reason) => {
      if (this.state === Sse.STATE_CLOSED) {
        return false
      }
      if (this.isActive()) {
        this.flush()
      }
      this.state = Sse.STATE_CLOSED
      this.clearReconnectTimer()
      const connectionId = this.connectionId
      if (connectionId !== undefined) {
        if (this.isPageActive) {
          this.isPageActive = false
          this.handleChangeActiveEvent(true, false)
        }
        this.clientClose = { connectionId, reason }
        const duration = this.getDuration()
        const params = new window_URLSearchParams()
        params.set('clientId', this.clientId)
        params.set('connectionId', connectionId)
        params.set('reason', reason)
        params.set('sseVersion', Sse.version)
        params.set('duration', String(duration.duration))
        params.set('sessionDuration', String(duration.sessionDuration))
        return navigator.sendBeacon(`${this.options.url}/connect/disconnect.do`, params)
      } else {
        return false
      }
    }

    this.getDuration = () => {
      const sessionDuration = Math.round(Number(window_sessionStorage.getItem(this.options.sseDurationKey) || 0))
      const duration = this.durationTime === null? 0 : Math.round((Date.now() - this.durationTime) / 1000)
      return {
        sessionDuration,
        duration
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
      const queryBuilder = new window_URLSearchParams()
      queryBuilder.append('connectionId', this.connectionId)
      for (const key in query) {
        queryBuilder.append(key, query[key])
      }
      try {
        return fetch(`${this.options.url}/connect/message/${path}.do?${queryBuilder.toString()}`, {
          method: 'POST',
          body: json_stringify(body),
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
      const queryBuilder = new window_URLSearchParams()
      queryBuilder.append('connectionId', this.connectionId)
      for (const key in query) {
        queryBuilder.append(key, query[key])
      }
      try {
        return fetch(`${this.options.url}/connect/upload/${path}.do?${queryBuilder.toString()}`, {
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

    // 挂机检测
    setInterval(() => {
      const leaveTimeout = this.options.leaveTimeout
      const oldActive = this.isPageActive
      const leaveTime = Date.now() - this.lastActiveTime
      const newActive = leaveTime < leaveTimeout
      // 状态发生改变, 恢复活跃使用 或 变成挂机状态.
      if (oldActive !== newActive) {
        this.handleChangeActiveEvent(oldActive, newActive)
      }
      this.isPageActive = newActive
    }, this.options.leaveCheckInterval)

    this.handleChangeActiveEvent = (oldActive, newActive) => {
      const duration = this.getDuration()
      if (newActive) {
        this.durationTime = Date.now()
      } else {
        window_sessionStorage.setItem(this.options.sseDurationKey, String(duration.sessionDuration + duration.duration))
        this.durationTime = null
      }
      const event = new window_MessageEvent('sse-change-active', {
        data: {oldActive, newActive, duration}
      })
      const eventListeners = this.options.eventListeners[event.type]
      if (eventListeners instanceof Function) {
        try {
          eventListeners.apply(this, [event])
        } catch (e) {
          console.warn(event.type + ' listeners error', e)
        }
      }
      this._dispatchEvent(event)
    }

    this.handleLastActiveTime = (event) => {
      if (window_requestAnimationFrame) {
        window_requestAnimationFrame(() => {
          this.lastActiveTime = Date.now()
        });
      } else {
        this.lastActiveTime = Date.now()
      }
    }
    document_addEventListener('mouseover', this.handleLastActiveTime, false)
    document_addEventListener('click', this.handleLastActiveTime, false)

    // 页签关闭时
    window_addEventListener('unload', () => {
      this._close('unload')
    }, false)

    window_addEventListener('beforeunload', () => {
      this._close('beforeunload')
    }, false)

    // 监听浏览器窗口切换时
    document_addEventListener('visibilitychange', () => {
      if (getVisibilityState() === 'visible') {
        this.newEventSource()
      } else {
        this._close('visibilitychange')
      }
    })

    this.newEventSource()
    this.options.windowGlobal.sse = this

    if (this.options.windowGlobal.Sse === undefined) {
      this.options.windowGlobal.Sse = Sse
    }
  }
}
