# sse-server

#### 介绍
sse协议的后端API, 比websocket轻量的实时通信

#### 软件架构
软件架构说明


#### 安装教程

1.  添加maven依赖, 在pom.xml中加入 [![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.wangzihaogithub/sse-server/badge.svg)](https://search.maven.org/search?q=g:com.github.wangzihaogithub%20AND%20a:sse-server)


        <!-- https://mvnrepository.com/artifact/com.github.wangzihaogithub/sse-server -->
        <dependency>
            <groupId>com.github.wangzihaogithub</groupId>
            <artifactId>sse-server</artifactId>
            <version>1.0.5</version>
        </dependency>
        
2.  配置业务逻辑 （后端）


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
        @RequestMapping("/sse/hr")
        @Slf4j
        public class HrController extends SseWebController<HrAccessUser> {
            @Override
            protected HrAccessUser getAccessUser() {
                return WebSecurityAccessFilter.getCurrentAccessUser();
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

3.  实现推送信息业务逻辑（后端）


            MyHrBellDTO bellDTO = new MyHrBellDTO();
            bellDTO.setCount(100);
            hrLocalConnectionService.sendByUserId(hrUserId,
                    SseEmitter.event()
                            .data(bellDTO)
                            .name("myHrBell")
            );
            
4.  编写业务逻辑 （前端） 

    前端代码 https://github.com/wangzihaogithub/sse-js.git

    1. Vue示例：
    
    
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
                }
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
