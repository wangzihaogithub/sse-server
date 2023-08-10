package com.github.sseserver.demo1;

//import com.github.netty.springboot.EnableNettyEmbedded;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;

/**
 * Websocket服务端 端口号:10005
 * 访问 http://localhost:10005/index.html 可以看效果
 *
 * @author wangzihao
 */
//@EnableNettyEmbedded
@SpringBootApplication
@ServletComponentScan
public class SseApplication80 {

    public static void main(String[] args) {
        SpringApplication.run(SseApplication80.class, args);
    }

}
