# sse-server

#### 介绍
sse协议的后端API, 比websocket轻量的实时通信, 

在LocalConnectionService和sse.js中封装了业务 (浏览器tab切换逻辑, 断线重连, 根据用户ID发送, 获取在线用户, 上线通知, 离线通知)


1. 只有用户当前能看到或正在使用的页签会保持链接. 如果用户不浏览会自动下线, 强实时在线.

2. 不需要前端引入任何js代码和任何依赖, 客户端代码在后端更新, 前端立即生效. 使用的是es6的前端语法, 动态import(接口)

3. 支持双向通信, 后端发送消息后会返回是否成功, 前端发送有可靠保证, 会自动重连, 成功后会自动将离线期间的请求继续发送.


        // 1.后端给前端推数据
        const listeners = {
           'myHunterBell': (event) => {console.log(event.data)},
           'xxx-xx': this.xx
         }
        sseEventListener('/sse/hr', listeners).then(sseConnection => {
           this.sseConnection = sseConnection
        })

        // 2.前端给后端送数据 当前连接发json请求
        sseConnection.send(path, body, query, headers).then(response =>{
            console.log(response)
        })
        // 3.前端给后端送文件 当前连接的文件上传
        const data = new FormData()
        data.set(name, file)
        sseConnection.upload(path, data, query, headers).then(response =>{
            console.log(response)
        })

4. 在nginx开启http2情况下, 可以和其他短链接ajax请求, 复用一个连接, 摆脱了浏览器单个域名下的最大连接数限制, 在客户网络繁忙或网卡老化的情况下有奇效, 这是websocket做不到的. 



#### 软件架构
软件架构说明


#### 安装教程

1.  添加maven依赖, 在pom.xml中加入 [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.wangzihaogithub/sse-server/badge.svg)](https://search.maven.org/search?q=g:com.github.wangzihaogithub%20AND%20a:sse-server)


        <!-- https://mvnrepository.com/artifact/com.github.wangzihaogithub/sse-server -->
        <dependency>
            <groupId>com.github.wangzihaogithub</groupId>
            <artifactId>sse-server</artifactId>
            <version>1.1.2</version>
        </dependency>
        
2.  配置业务逻辑 （后端）


        // 实现AccessUser 可以使用sendByUserId(). 
        // 实现AccessToken 可以使用sendByAccessToken(), (多客户端登陆系统)
        // 实现CustomerAccessUser 可以使用sendByCustomerId(), (多租户系统)
        @Data
        public class HrAccessUser implements AccessToken, AccessUser, CustomerAccessUser {
            private String accessToken;
            private Integer id;
            private String name;
            private Integer customerId;
        }

        // 支持多系统
        @Bean
        public LocalConnectionService hrLocalConnectionService() {
            // hr系统 用hrLocalConnectionService
            return new LocalConnectionServiceImpl();
        }
    
        @Bean
        public LocalConnectionService hunterLocalConnectionService() {
            // hunter系统 用hunterLocalConnectionService
            return new LocalConnectionServiceImpl();
        }
        
        
        /**
         * 消息事件推送 (非分布式)
         * <p>
         * 1. 如果用nginx代理, 要加下面的配置
         * # 长连接配置
         * proxy_buffering off;
         * proxy_read_timeout 7200s;
         * proxy_pass http://172.17.83.249:9095;
         * proxy_http_version 1.1; #nginx默认是http1.0, 改为1.1 支持长连接, 和后端保持长连接,复用,防止出现文件句柄打开数量过多的错误
         * proxy_set_header Connection ""; # 去掉Connection的close字段
         *
         * @author hao 2021年12月7日19:29:51
         */
        @RestController
        @RequestMapping("/sse/hr") // 这里自定义地址, 给前端这个地址连
        public class HrController extends SseWebController<HrAccessUser> {
            @Override
            protected HrAccessUser getAccessUser() {
                return WebSecurityAccessFilter.getCurrentAccessUser(super.request);
            }
        
            @Autowired
            @Override
            public void setLocalConnectionService(LocalConnectionService hrLocalConnectionService) {
                super.setLocalConnectionService(hrLocalConnectionService);
            }
        
            @Override
            protected Object wrapOkResponse(Object result) {
                return ResponseData.success(result);
            }
        }


3.  接口示例: 实现推送信息业务逻辑（后端）


            // 获取所有在线用户
            List<ACCESS_USER> userList = hrLocalConnectionService.getUsers();
            // 上线通知
            hrLocalConnectionService.addConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);
            // 离线通知
            hrLocalConnectionService.addDisConnectListener(Consumer<SseEmitter<ACCESS_USER>> consumer);
            
            // 推送消息 (根据用户ID)
            MyHrBellDTO bellDTO = new MyHrBellDTO();
            bellDTO.setCount(100);
            int successCount = hrLocalConnectionService.sendByUserId(hrUserId,
                    SseEmitter.event()
                            .data(bellDTO)
                            .name("myHrBell")
            );
            
            // 推送消息 (根据登陆令牌)
            int sendByAccessToken(accessToken, message)
            
            // 推送消息 (根据租户ID)
            int sendByCustomerId(customerId, message)
                        
            // 推送消息 (根据自定义信道)
            int sendByChannel(channel, message)
            
            // 推送消息 (群发)
            int sendAll(message);
            
            // 默认自带http接口 (分页查询当前在线用户) 在SseWebController
            http://localhost:8080/sse/hr/users
            
            // 默认自带http接口 (分页查询当前在线连接) 在SseWebController
            http://localhost:8080/sse/hr/connections
            
            // 默认自带http接口 (踢掉用户) 在SseWebController
            http://localhost:8080/sse/hr/disconnectUser
                        
           
4.  编写业务逻辑 （前端） 
            
            
1. 强烈推荐! 原生html示例, 或Vue (不需要前端引入任何依赖sse.js, 只需要这几行代码)
    
    
    
          1. 函数声明, 在index.html或Vue的index.html里加入代码
          
          <script>
                function sseEventListener(url, eventListeners) {
                  return import(url).then(module => new module.default({url, eventListeners}))
                }
          </script>
  
         2. 使用
         
            const listeners = {
               'myHunterBell': this.onHunterBell,
               'xxx-xx': this.xx
             }
            sseEventListener('/sse/hr', listeners).then(sseConnection => {
               this.sseConnection = sseConnection
            })
             
           
2. Vue示例(方式1)：
     
     
     
            下载前端代码 https://github.com/wangzihaogithub/sse-js.git, 或复制本项目中的 /src/resources/sse.js

            import Sse from '../util/sse.js'
    
            mounted() {
              // 来自HR系统的服务端推送
              this.hrSse = new Sse({
                url : '/sse/hr',
                eventListeners:{
                  'myHrBell': this.onHrBell,
                  'xxx-xx': this.xx
                }
              })
              
              // 来自猎头系统的服务端推送
              this.hunterSse = new Sse({
                url : '/sse/hunter',
                eventListeners:{
                  'myHunterBell': this.onHunterBell,
                  'xxx-xx': this.xx
                },
                query: { arg1 : 123 }, // 非必填 - 连接时携带的参数 
                clientId: '自定义设备ID', // 非必填
                accessTimestamp: Date.now(), // 非必填 - 接入时间
                reconnectTime: 5000, // 非必填 - 网络错误时的重连时间
              })
            },
            beforeDestroy() {
              this.hrSse.destroy()
              this.hunterSse.destroy()
            }


#### 使用说明

1.  xxxx
2.  xxxx
3.  xxxx

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request


#### 特技

1.  使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2.  Gitee 官方博客 [blog.gitee.com](https://blog.gitee.com)
3.  你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解 Gitee 上的优秀开源项目
4.  [GVP](https://gitee.com/gvp) 全称是 Gitee 最有价值开源项目，是综合评定出的优秀开源项目
5.  Gitee 官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6.  Gitee 封面人物是一档用来展示 Gitee 会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)
