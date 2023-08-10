package com.github.sseserver.demo1.controller;

import com.github.sseserver.local.SseWebController;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.InputStream;

@Controller
@RequestMapping
public class IndexController {

    /**
     * 前端文件
     */
    @RequestMapping("/index.html")
    public Object index() {
        InputStream stream = SseWebController.class.getResourceAsStream("/html/index.html");
        Resource body = new InputStreamResource(stream);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }
}
